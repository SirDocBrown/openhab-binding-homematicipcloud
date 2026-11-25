/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homematicipcloud.internal;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.osgi.framework.FrameworkUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One authenticated client per bridge. No credentials are logged or sent to redirected hosts.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class Cloud implements AutoCloseable {
    interface Transport {
        Reply send(HttpRequest request) throws Exception;

        default WebSocket connect(WebSocket.Builder builder, URI endpoint, WebSocket.Listener listener) {
            return builder.buildAsync(endpoint, listener).join();
        }
    }

    record Reply(int statusCode, String body, @Nullable String retryAfter) {
        Reply(int statusCode, String body) {
            this(statusCode, body, "");
        }
    }

    private final HttpClient http;
    private final Transport transport;
    private final String serial;
    private final String clientAuth;
    private final JsonObject characteristics = new JsonObject();
    private @Nullable URI rest;
    private @Nullable URI events;
    private String token;

    Cloud(String serial, String token) throws Exception {
        this(serial, token, null);
    }

    Cloud(String serial, String token, @Nullable Transport testTransport) throws Exception {
        this.serial = serial.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!this.serial.matches("[0-9A-F]{24}")) {
            throw new IllegalArgumentException("SGTIN: 24 Hex-Zeichen erforderlich");
        }
        this.token = token;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        transport = testTransport != null ? testTransport : request -> {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(),
                    response.headers().firstValue("Retry-After").orElse(""));
        };
        byte[] bytes = (this.serial + "jiLpVitHvWnIGD1yo7MA").getBytes(StandardCharsets.UTF_8);
        clientAuth = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
        JsonObject client = new JsonObject();
        client.addProperty("apiVersion", "10");
        client.addProperty("applicationIdentifier", "openHAB");
        var bundle = FrameworkUtil.getBundle(Cloud.class);
        client.addProperty("applicationVersion", bundle == null ? "unknown" : bundle.getVersion().toString());
        client.addProperty("deviceManufacturer", "openHAB");
        client.addProperty("deviceType", "Computer");
        client.addProperty("language", "de_DE");
        client.addProperty("osType", "Linux");
        client.addProperty("osVersion", "openHAB 5");
        characteristics.add("clientCharacteristics", client);
        characteristics.addProperty("id", this.serial);
    }

    static final Duration MAX_THROTTLE = Duration.ofHours(6);

    static final class Status extends IOException {
        private static final long serialVersionUID = 1L;

        final int code;

        final String retryAfter;

        Status(int code, @Nullable String retryAfter) {
            super("Cloud HTTP " + code);
            this.code = code;
            this.retryAfter = retryAfter == null ? "" : retryAfter;
        }

        long retryAt(long now) {
            long minimum = now + Duration.ofMinutes(15).toMillis();
            try {
                long seconds = new BigInteger(retryAfter.strip()).max(BigInteger.ZERO)
                        .min(BigInteger.valueOf(MAX_THROTTLE.toSeconds())).longValueExact();
                return Math.max(minimum, now + seconds * 1000);
            } catch (NumberFormatException e) {
                try {
                    long date = ZonedDateTime.parse(retryAfter.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli();
                    return Math.clamp(date, minimum, now + MAX_THROTTLE.toMillis());
                } catch (DateTimeParseException ignored) {
                    return minimum;
                }
            }
        }
    }

    private JsonObject post(URI uri, JsonObject payload, String pin, boolean authenticated, boolean lookup)
            throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        if (!lookup) {
            request.header("VERSION", "12").header("CLIENTAUTH", clientAuth);
        }
        if (authenticated) {
            request.header("AUTHTOKEN", token);
        }
        if (!pin.isBlank()) {
            request.header("PIN", pin);
        }
        var response = transport.send(request.build());
        if (uri.getPath().endsWith("/isRequestAcknowledged") && response.statusCode() != 200) {
            throw new Status(response.statusCode(), response.retryAfter());
        }
        if (response.statusCode() / 100 != 2) {
            throw new Status(response.statusCode(), response.retryAfter());
        }
        if (response.body().isBlank()) {
            return new JsonObject();
        }
        JsonElement result = JsonParser.parseString(response.body());
        if (result.isJsonObject() && result.getAsJsonObject().has("errorCode")) {
            throw new IOException("Cloud lehnt die Anfrage ab");
        }
        return result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    void lookup() throws Exception {
        JsonObject result = post(URI.create("https://lookup.homematic.com:48335/getHost"), characteristics, "", false,
                true);
        rest = trusted(result.get("urlREST").getAsString(), "https");
        events = trusted(result.get("urlWebSocket").getAsString(), "wss");
    }

    static URI trusted(String value, String scheme) throws IOException {
        URI uri = URI.create(value);
        if (!scheme.equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || !uri.getHost().toLowerCase(Locale.ROOT).endsWith(".homematic.com")) {
            throw new IOException("Ungültiger Cloud-Endpunkt");
        }
        return uri;
    }

    JsonObject call(String path, JsonObject body) throws Exception {
        return post(java.util.Objects.requireNonNull(rest, "Cloud lookup required").resolve("/hmip/" + path), body, "",
                true, false);
    }

    JsonObject snapshot() throws Exception {
        JsonObject result = call("home/getCurrentState", characteristics);
        if (!result.has("devices") || !result.get("devices").isJsonObject()) {
            throw new IOException("Geräteliste fehlt");
        }
        return result;
    }

    JsonObject startPairing(String pin) throws Exception {
        JsonObject id = new JsonObject();
        id.addProperty("deviceId", UUID.randomUUID().toString());
        JsonObject request = id.deepCopy();
        request.addProperty("deviceName", "openHAB Homematic IP Cloud");
        request.addProperty("sgtin", serial);
        post(java.util.Objects.requireNonNull(rest, "Cloud lookup required").resolve("/hmip/auth/connectionRequest"),
                request, pin, false, false);
        return id;
    }

    String finishPairing(JsonObject id, String pin) throws Exception {
        try {
            post(java.util.Objects.requireNonNull(rest, "Cloud lookup required")
                    .resolve("/hmip/auth/isRequestAcknowledged"), id, pin, false, false);
        } catch (Status e) {
            if (e.code == 202 || e.code == 204 || e.code == 403 || e.code == 404) {
                return "";
            }
            throw e;
        }
        var issued = post(
                java.util.Objects.requireNonNull(rest, "Cloud lookup required").resolve("/hmip/auth/requestAuthToken"),
                id, pin, false, false);
        String candidate = issued.get("authToken").getAsString();
        var confirm = id.deepCopy();
        confirm.addProperty("authToken", candidate);
        post(java.util.Objects.requireNonNull(rest, "Cloud lookup required").resolve("/hmip/auth/confirmAuthToken"),
                confirm, pin, false, false);
        token = candidate;
        return token;
    }

    WebSocket listen(WebSocket.Listener listener) throws Status {
        var builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).header("CLIENTAUTH", clientAuth)
                .header("AUTHTOKEN", token);
        try {
            return transport.connect(builder, java.util.Objects.requireNonNull(events, "Cloud lookup required"),
                    listener);
        } catch (CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException handshake) {
                var response = handshake.getResponse();
                throw new Status(response.statusCode(), response.headers().firstValue("Retry-After").orElse(""));
            }
            throw e;
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
