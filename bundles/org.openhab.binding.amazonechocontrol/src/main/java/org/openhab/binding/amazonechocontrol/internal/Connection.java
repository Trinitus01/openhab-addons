/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.amazonechocontrol.internal;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.eclipse.smarthome.core.util.HexUtils;
import org.eclipse.smarthome.io.net.http.HttpClientFactory;
import org.openhab.binding.amazonechocontrol.internal.jsons.*;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonActivities.Activity;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonAnnouncementTarget.TargetDevice;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonAscendingAlarm.AscendingAlarmModel;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonAutomation.Payload;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonAutomation.Trigger;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonBootstrapResult.Authentication;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonDeviceNotificationState.DeviceNotificationState;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonDevices.Device;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonExchangeTokenResponse.Cookie;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonRegisterAppResponse.Bearer;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonRegisterAppResponse.DeviceInfo;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonRegisterAppResponse.Extensions;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonRegisterAppResponse.Response;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonRegisterAppResponse.Success;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonRegisterAppResponse.Tokens;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonSmartHomeDevices.SmartHomeDevice;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonSmartHomeGroups.SmartHomeGroup;
import org.openhab.binding.amazonechocontrol.internal.jsons.JsonWakeWords.WakeWord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link Connection} is responsible for the connection to the amazon server
 * and handling of the commands
 *
 * @author Michael Geramb - Initial contribution
 */
@NonNullByDefault
public class Connection {
    private static final String THING_THREADPOOL_NAME = "thingHandler";
    private static final long EXPIRES_IN = 432000; // five days
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)\\bcharset=\\s*\"?([^\\s;\"]*)");

    protected final ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool(THING_THREADPOOL_NAME);

    private final Logger logger = LoggerFactory.getLogger(Connection.class);

    private final CookieManager cookieManager = new CookieManager();
    private String amazonSite = "amazon.com";
    private String alexaServer = "https://alexa.amazon.com";
    private String frc;
    private String serial;
    private String deviceId;

    private @Nullable String refreshToken;
    private @Nullable Date loginTime;
    private @Nullable Date verifyTime;
    private long renewTime = 0;
    private @Nullable String deviceName;
    private @Nullable String accountCustomerId;
    private @Nullable String customerName;

    private Map<Integer, Announcement> announcements = new LinkedHashMap<>();
    private Map<Integer, TextToSpeech> textToSpeeches = new LinkedHashMap<>();
    private @Nullable ScheduledFuture<?> announcementTimer;
    private @Nullable ScheduledFuture<?> textToSpeechTimer;

    private final Gson gson;
    private final Gson gsonWithNullSerialization;

    private LinkedBlockingQueue<Announcement> announcementQueue = new LinkedBlockingQueue<>();
    private AtomicBoolean announcementQueueRunning = new AtomicBoolean();
    private @Nullable ScheduledFuture<?> announcementSenderUnblockFuture;
    private LinkedBlockingQueue<TextToSpeech> textToSpeechQueue = new LinkedBlockingQueue<>();
    private AtomicBoolean textToSpeechQueueRunning = new AtomicBoolean();
    private @Nullable ScheduledFuture<?> textToSpeechSenderUnblockFuture;
    private LinkedBlockingQueue<JsonObject> sequenceNodeQueue = new LinkedBlockingQueue<>();
    private AtomicBoolean sequenceNodeQueueRunning = new AtomicBoolean();
    private @Nullable ScheduledFuture<?> sequenceNodeSenderUnblockFuture;

    private final HttpClient httpClient;

    public Connection(@Nullable Connection oldConnection, Gson gson, HttpClientFactory httpClientFactory) {
        this.gson = gson;
        this.httpClient = httpClientFactory.createHttpClient("amazonechocontrol");
        this.httpClient.setUserAgentField(
                new HttpField(HttpHeader.USER_AGENT, "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/11.4.1/iPhone"));

        String frc = null;
        String serial = null;
        String deviceId = null;
        if (oldConnection != null) {
            frc = oldConnection.getFrc();
            serial = oldConnection.getSerial();
            deviceId = oldConnection.getDeviceId();
        }
        Random rand = new Random();
        if (frc != null) {
            this.frc = frc;
        } else {
            // generate frc
            byte[] frcBinary = new byte[313];
            rand.nextBytes(frcBinary);
            this.frc = Base64.getEncoder().encodeToString(frcBinary);
        }
        if (serial != null) {
            this.serial = serial;
        } else {
            // generate serial
            byte[] serialBinary = new byte[16];
            rand.nextBytes(serialBinary);
            this.serial = HexUtils.bytesToHex(serialBinary);
        }
        if (deviceId != null) {
            this.deviceId = deviceId;
        } else {
            // generate device id
            byte[] bytes = new byte[16];
            rand.nextBytes(bytes);
            String hexStr = HexUtils.bytesToHex(bytes).toUpperCase();
            this.deviceId = HexUtils.bytesToHex(hexStr.getBytes()) + "23413249564c5635564d32573831";
        }

        // setAmazonSite(amazonSite);
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonWithNullSerialization = gsonBuilder.create();
    }

    private void setAmazonSite(@Nullable String amazonSite) {
        String correctedAmazonSite = amazonSite != null ? amazonSite : "amazon.com";
        if (correctedAmazonSite.toLowerCase().startsWith("http://")) {
            correctedAmazonSite = correctedAmazonSite.substring(7);
        }
        if (correctedAmazonSite.toLowerCase().startsWith("https://")) {
            correctedAmazonSite = correctedAmazonSite.substring(8);
        }
        if (correctedAmazonSite.toLowerCase().startsWith("www.")) {
            correctedAmazonSite = correctedAmazonSite.substring(4);
        }
        if (correctedAmazonSite.toLowerCase().startsWith("alexa.")) {
            correctedAmazonSite = correctedAmazonSite.substring(6);
        }
        this.amazonSite = correctedAmazonSite;
        alexaServer = "https://alexa." + this.amazonSite;
    }

    public @Nullable Date tryGetLoginTime() {
        return loginTime;
    }

    public @Nullable Date tryGetVerifyTime() {
        return verifyTime;
    }

    public String getFrc() {
        return frc;
    }

    public String getSerial() {
        return serial;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getAmazonSite() {
        return amazonSite;
    }

    public String getAlexaServer() {
        return alexaServer;
    }

    public String getDeviceName() {
        String deviceName = this.deviceName;
        if (deviceName == null) {
            return "Unknown";
        }
        return deviceName;
    }

    public String getCustomerId() {
        String customerId = this.accountCustomerId;
        if (customerId == null) {
            return "Unknown";
        }
        return customerId;
    }

    public String getCustomerName() {
        String customerName = this.customerName;
        if (customerName == null) {
            return "Unknown";
        }
        return customerName;
    }

    public String serializeLoginData() {
        Date loginTime = this.loginTime;
        if (refreshToken == null || loginTime == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("7\n"); // version
        builder.append(frc);
        builder.append("\n");
        builder.append(serial);
        builder.append("\n");
        builder.append(deviceId);
        builder.append("\n");
        builder.append(refreshToken);
        builder.append("\n");
        builder.append(amazonSite);
        builder.append("\n");
        builder.append(deviceName);
        builder.append("\n");
        builder.append(accountCustomerId);
        builder.append("\n");
        builder.append(loginTime.getTime());
        builder.append("\n");
        List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
        builder.append(cookies.size());
        builder.append("\n");
        for (HttpCookie cookie : cookies) {
            writeValue(builder, cookie.getName());
            writeValue(builder, cookie.getValue());
            writeValue(builder, cookie.getComment());
            writeValue(builder, cookie.getCommentURL());
            writeValue(builder, cookie.getDomain());
            writeValue(builder, cookie.getMaxAge());
            writeValue(builder, cookie.getPath());
            writeValue(builder, cookie.getPortlist());
            writeValue(builder, cookie.getVersion());
            writeValue(builder, cookie.getSecure());
            writeValue(builder, cookie.getDiscard());
        }
        return builder.toString();
    }

    private void writeValue(StringBuilder builder, @Nullable Object value) {
        if (value == null) {
            builder.append('0');
        } else {
            builder.append('1');
            builder.append("\n");
            builder.append(value.toString());
        }
        builder.append("\n");
    }

    private String readValue(Scanner scanner) {
        if (scanner.nextLine().equals("1")) {
            String result = scanner.nextLine();
            if (result != null) {
                return result;
            }
        }
        return "";
    }

    public boolean tryRestoreLogin(@Nullable String data, @Nullable String overloadedDomain) {
        Date loginTime = tryRestoreSessionData(data, overloadedDomain);
        if (loginTime != null) {
            try {
                if (verifyLogin()) {
                    this.loginTime = loginTime;
                    return true;
                }
            } catch (InterruptedException | ExecutionException e) {
            }
        }
        return false;
    }

    private @Nullable Date tryRestoreSessionData(@Nullable String data, @Nullable String overloadedDomain) {
        // verify store data
        if (data == null || data.isEmpty()) {
            return null;
        }
        Scanner scanner = new Scanner(data);
        String version = scanner.nextLine();
        // check if serialize version is supported
        if (!"5".equals(version) && !"6".equals(version) && !"7".equals(version)) {
            scanner.close();
            return null;
        }
        int intVersion = Integer.parseInt(version);

        frc = scanner.nextLine();
        serial = scanner.nextLine();
        deviceId = scanner.nextLine();

        // Recreate session and cookies
        refreshToken = scanner.nextLine();
        String domain = scanner.nextLine();
        if (overloadedDomain != null) {
            domain = overloadedDomain;
        }
        setAmazonSite(domain);

        deviceName = scanner.nextLine();

        if (intVersion > 5) {
            String accountCustomerId = scanner.nextLine();
            // Note: version 5 have wrong customer id serialized.
            // Only use it, if it at least version 6 of serialization
            if (intVersion > 6) {
                if (!"null".equals(accountCustomerId)) {
                    this.accountCustomerId = accountCustomerId;
                }
            }
        }

        Date loginTime = new Date(Long.parseLong(scanner.nextLine()));
        CookieStore cookieStore = cookieManager.getCookieStore();
        cookieStore.removeAll();

        Integer numberOfCookies = Integer.parseInt(scanner.nextLine());
        for (Integer i = 0; i < numberOfCookies; i++) {
            String name = readValue(scanner);
            String value = readValue(scanner);

            HttpCookie clientCookie = new HttpCookie(name, value);
            clientCookie.setComment(readValue(scanner));
            clientCookie.setCommentURL(readValue(scanner));
            clientCookie.setDomain(readValue(scanner));
            clientCookie.setMaxAge(Long.parseLong(readValue(scanner)));
            clientCookie.setPath(readValue(scanner));
            clientCookie.setPortlist(readValue(scanner));
            clientCookie.setVersion(Integer.parseInt(readValue(scanner)));
            clientCookie.setSecure(Boolean.parseBoolean(readValue(scanner)));
            clientCookie.setDiscard(Boolean.parseBoolean(readValue(scanner)));

            cookieStore.add(null, clientCookie);
        }
        scanner.close();
        try {
            checkRenewSession();

            String accountCustomerId = this.accountCustomerId;
            if (accountCustomerId == null || accountCustomerId.isEmpty()) {
                List<Device> devices = this.getDeviceList();
                for (Device device : devices) {
                    final String serial = this.serial;
                    if (serial != null && serial.equals(device.serialNumber)) {
                        this.accountCustomerId = device.deviceOwnerCustomerId;
                        break;
                    }
                }
                accountCustomerId = this.accountCustomerId;
                if (accountCustomerId == null || accountCustomerId.isEmpty()) {
                    for (Device device : devices) {
                        if ("This Device".equals(device.accountName)) {
                            this.accountCustomerId = device.deviceOwnerCustomerId;
                            String serial = device.serialNumber;
                            if (serial != null) {
                                this.serial = serial;
                            }
                            break;
                        }
                    }
                }
            }
        } catch (URISyntaxException | IOException | ConnectionException | InterruptedException | ExecutionException e) {
            logger.debug("Getting account customer Id failed", e);
        }
        return loginTime;
    }

    private @Nullable Authentication tryGetBootstrap() throws ExecutionException, InterruptedException {
        ContentResponse contentResponse = makeRequest("GET", alexaServer + "/api/bootstrap", null, false, false, null,
                0).get();
        String contentType = contentResponse.getMediaType();
        if (contentType != null && contentType.toLowerCase().startsWith("application/json")) {
            try {
                String bootstrapResultJson = contentResponse.getContentAsString();
                JsonBootstrapResult result = parseJson(bootstrapResultJson, JsonBootstrapResult.class);
                if (result != null) {
                    Authentication authentication = result.authentication;
                    if (authentication != null && authentication.authenticated) {
                        this.customerName = authentication.customerName;
                        if (this.accountCustomerId == null) {
                            this.accountCustomerId = authentication.customerId;
                        }
                        return authentication;
                    }
                }
            } catch (JsonSyntaxException | IllegalStateException e) {
                logger.info("No valid json received", e);
                return null;
            }
        }
        return null;
    }

    public CompletableFuture<String> makeRequestAndReturnString(String url) {
        return makeRequestAndReturnString("GET", url, null, false, null);
    }

    public CompletableFuture<String> makeRequestAndReturnString(String verb, String url, @Nullable String postData,
            boolean json, @Nullable Map<String, String> customHeaders) {
        return makeRequest(verb, url, postData, json, true, customHeaders, 0)
                .thenApply(ContentResponse::getContentAsString);
    }

    private static class FullRequest {
        public String verb;
        public String url;
        public @Nullable String postData;
        public boolean json;
        public boolean autoRedirect;
        public Map<String, String> customHeaders;
        public int badRequestRepeats;

        public CompletableFuture<ContentResponse> returnedValue = new CompletableFuture<ContentResponse>();

        public FullRequest(String verb, String url, @Nullable String postData, boolean json, boolean autoRedirect,
                @Nullable Map<String, String> customHeaders, int badRequestRepeats) {
            this.verb = verb;
            this.url = url;
            this.postData = postData;
            this.json = json;
            this.autoRedirect = autoRedirect;
            this.customHeaders = customHeaders != null ? customHeaders : Collections.emptyMap();
            this.badRequestRepeats = badRequestRepeats;
        }
    }

    private static class CommunicationException extends Exception {
        public CommunicationException() {
            super();
        }

        public CommunicationException(String message) {
            super(message);
        }
    }

    private final ConcurrentLinkedDeque<FullRequest> fullRequestQueue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean requestQueueRunning = new AtomicBoolean();

    private CompletableFuture<ContentResponse> makeRequest(String verb, String url, @Nullable String postData,
            boolean json, boolean autoredirect, @Nullable Map<String, String> customHeaders, int badRequestRepeats) {
        FullRequest fullRequest = new FullRequest(verb, url, postData, json, autoredirect, customHeaders,
                badRequestRepeats);
        fullRequestQueue.add(fullRequest);

        if (!requestQueueRunning.compareAndSet(true, true)) {
            processRequestQueue();
        }

        return fullRequest.returnedValue;
    }

    public void processRequestQueue() {
        if (!requestQueueRunning.compareAndSet(!fullRequestQueue.isEmpty(), !fullRequestQueue.isEmpty())) {
            // queue is empty,stop processing
            return;
        }

        FullRequest fullRequest = fullRequestQueue.poll();

        String currentUrl = fullRequest.url;

        int redirectCounter = 0;
        // loop for handling redirect and bad request, using automatic redirect is not
        // possible, because all response headers must be catched
        while (true) {
            int code;
            Request request = httpClient.newRequest(currentUrl);
            try {
                logger.debug("Make request to {}", fullRequest.url);
                request.method(fullRequest.verb);
                request.header("Accept-Language", "en-US");
                request.header("Accept-Encoding", "gzip");
                request.header("DNT", "1");
                request.header("Upgrade-Insecure-Requests", "1");
                fullRequest.customHeaders.forEach((k, v) -> {
                    if (v != null && !v.isEmpty()) {
                        request.header(k, v);
                    }
                });
                request.followRedirects(false);

                // add cookies
                URI uri = request.getURI();

                if (!fullRequest.customHeaders.containsKey("Cookie")) {
                    for (HttpCookie cookie : cookieManager.getCookieStore().get(uri)) {
                        request.cookie(cookie);
                        if (cookie.getName().equals("csrf")) {
                            request.header("csrf", cookie.getValue());
                        }
                    }
                }
                if (fullRequest.postData != null) {
                    // post data
                    logger.debug("{}: {}", fullRequest.verb, fullRequest.postData);
                    String contentType = fullRequest.json ? "application/json; charset=UTF-8"
                            : "application/x-www-form-urlencoded";
                    request.content(new StringContentProvider(fullRequest.postData, StandardCharsets.UTF_8),
                            contentType);

                    if ("POST".equals(fullRequest.verb)) {
                        request.header("Expect", "100-continue");
                    }
                }

                ContentResponse response = request.send();
                // handle result
                code = response.getStatus();
                String location = null;

                // handle response headers
                for (HttpField header : response.getHeaders()) {
                    String key = header.getName();
                    if (key != null && !key.isEmpty()) {
                        if (key.equalsIgnoreCase("Location")) {
                            // get redirect location
                            location = header.getValue();
                            if (location != null && !location.isEmpty()) {
                                location = uri.resolve(location).toString();
                                // check for https
                                if (location.toLowerCase().startsWith("http://")) {
                                    // always use https
                                    location = "https://" + location.substring(7);
                                    logger.debug("Redirect corrected to {}", location);
                                }
                            }
                        }
                    }
                }

                if (code == HttpStatus.BAD_REQUEST_400 && fullRequest.badRequestRepeats > 0) {
                    // re-add to front of queue
                    logger.debug("Retry call to {}", fullRequest.url);
                    fullRequest.badRequestRepeats--;
                    fullRequestQueue.offerFirst(fullRequest);
                } else if (code == HttpStatus.OK_200) {
                    // request successful
                    logger.debug("Call to {} succeeded", fullRequest.url);
                    fullRequest.returnedValue.complete(response);
                } else if (code == HttpStatus.MOVED_TEMPORARILY_302 && location != null) {
                    // follow redirect
                    logger.debug("Redirected to {}", location);
                    redirectCounter++;
                    if (redirectCounter > 30) {
                        fullRequest.returnedValue.completeExceptionally(new ConnectionException("Too many redirects"));
                    }
                    currentUrl = location;
                    if (!fullRequest.autoRedirect) {
                        continue;
                    } else {
                        fullRequest.returnedValue.completeExceptionally(
                                new CommunicationException("Redirect requested but forbidden by request settings."));
                    }
                } else {
                    fullRequest.returnedValue.completeExceptionally(new HttpException(code,
                            fullRequest.verb + " url '" + fullRequest.url + "' failed: " + response.getStatus()));
                }
            } catch (Exception e) {
                logger.warn("Request to url '{}' fails with unknown error", fullRequest.url, e);
                fullRequest.returnedValue.completeExceptionally(e);
            }
            break;
        }
        scheduler.schedule(this::processRequestQueue, 500, TimeUnit.MILLISECONDS);
    }

    public String registerConnectionAsApp(String oAutRedirectUrl)
            throws ConnectionException, IOException, URISyntaxException, ExecutionException, InterruptedException {
        URI oAutRedirectUri = new URI(oAutRedirectUrl);

        Map<String, String> queryParameters = new LinkedHashMap<>();
        String query = oAutRedirectUri.getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            queryParameters.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()));
        }
        String accessToken = queryParameters.get("openid.oa2.access_token");

        Map<String, String> cookieMap = new HashMap<>();

        List<JsonWebSiteCookie> webSiteCookies = new ArrayList<>();
        for (HttpCookie cookie : getSessionCookies("https://www.amazon.com")) {
            cookieMap.put(cookie.getName(), cookie.getValue());
            webSiteCookies.add(new JsonWebSiteCookie(cookie.getName(), cookie.getValue()));
        }

        JsonWebSiteCookie[] webSiteCookiesArray = new JsonWebSiteCookie[webSiteCookies.size()];
        webSiteCookiesArray = webSiteCookies.toArray(webSiteCookiesArray);

        JsonRegisterAppRequest registerAppRequest = new JsonRegisterAppRequest(serial, accessToken, frc,
                webSiteCookiesArray);
        String registerAppRequestJson = gson.toJson(registerAppRequest);

        HashMap<String, String> registerHeaders = new HashMap<>();
        registerHeaders.put("x-amzn-identity-auth-domain", "api.amazon.com");

        String registerAppResultJson = makeRequestAndReturnString("POST", "https://api.amazon.com/auth/register",
                registerAppRequestJson, true, registerHeaders).get();
        JsonRegisterAppResponse registerAppResponse = parseJson(registerAppResultJson, JsonRegisterAppResponse.class);

        if (registerAppResponse == null) {
            throw new ConnectionException("Error: No response received from register application");
        }
        Response response = registerAppResponse.response;
        if (response == null) {
            throw new ConnectionException("Error: No response received from register application");
        }
        Success success = response.success;
        if (success == null) {
            throw new ConnectionException("Error: No success received from register application");
        }
        Tokens tokens = success.tokens;
        if (tokens == null) {
            throw new ConnectionException("Error: No tokens received from register application");
        }
        Bearer bearer = tokens.bearer;
        if (bearer == null) {
            throw new ConnectionException("Error: No bearer received from register application");
        }
        this.refreshToken = bearer.refreshToken;
        if (this.refreshToken == null || this.refreshToken.isEmpty()) {
            throw new ConnectionException("Error: No refresh token received");
        }
        try {
            exchangeToken();
            // Check which is the owner domain
            String usersMeResponseJson = makeRequestAndReturnString("GET",
                    "https://alexa.amazon.com/api/users/me?platform=ios&version=2.2.223830.0", null, false, null).get();
            JsonUsersMeResponse usersMeResponse = parseJson(usersMeResponseJson, JsonUsersMeResponse.class);
            if (usersMeResponse == null) {
                throw new IllegalArgumentException("Received no response on me-request");
            }
            URI uri = new URI(usersMeResponse.marketPlaceDomainName);
            String host = uri.getHost();

            // Switch to owner domain
            setAmazonSite(host);
            exchangeToken();
            tryGetBootstrap();
        } catch (Exception e) {
            logout();
            throw e;
        }
        String deviceName = null;
        Extensions extensions = success.extensions;
        if (extensions != null) {
            DeviceInfo deviceInfo = extensions.deviceInfo;
            if (deviceInfo != null) {
                deviceName = deviceInfo.deviceName;
            }
        }
        if (deviceName == null) {
            deviceName = "Unknown";
        }
        this.deviceName = deviceName;
        return deviceName;
    }

    private void exchangeToken() throws IOException, URISyntaxException, ExecutionException, InterruptedException {
        this.renewTime = 0;
        String cookiesJson = "{\"cookies\":{\"." + getAmazonSite() + "\":[]}}";
        String cookiesBase64 = Base64.getEncoder().encodeToString(cookiesJson.getBytes());

        String exchangePostData = "di.os.name=iOS&app_version=2.2.223830.0&domain=." + getAmazonSite()
                + "&source_token=" + URLEncoder.encode(this.refreshToken, "UTF8")
                + "&requested_token_type=auth_cookies&source_token_type=refresh_token&di.hw.version=iPhone&di.sdk.version=6.10.0&cookies="
                + cookiesBase64 + "&app_name=Amazon%20Alexa&di.os.version=11.4.1";

        HashMap<String, String> exchangeTokenHeader = new HashMap<>();
        exchangeTokenHeader.put("Cookie", "");

        String exchangeTokenJson = makeRequestAndReturnString("POST",
                "https://www." + getAmazonSite() + "/ap/exchangetoken", exchangePostData, false, exchangeTokenHeader)
                        .get();
        JsonExchangeTokenResponse exchangeTokenResponse = gson.fromJson(exchangeTokenJson,
                JsonExchangeTokenResponse.class);

        org.openhab.binding.amazonechocontrol.internal.jsons.JsonExchangeTokenResponse.Response response = exchangeTokenResponse.response;
        if (response != null) {
            org.openhab.binding.amazonechocontrol.internal.jsons.JsonExchangeTokenResponse.Tokens tokens = response.tokens;
            if (tokens != null) {
                @Nullable
                Map<String, Cookie[]> cookiesMap = tokens.cookies;
                if (cookiesMap != null) {
                    for (String domain : cookiesMap.keySet()) {
                        Cookie[] cookies = cookiesMap.get(domain);
                        for (Cookie cookie : cookies) {
                            if (cookie != null) {
                                HttpCookie httpCookie = new HttpCookie(cookie.name, cookie.value);
                                httpCookie.setPath(cookie.path);
                                httpCookie.setDomain(domain);
                                Boolean secure = cookie.secure;
                                if (secure != null) {
                                    httpCookie.setSecure(secure);
                                }
                                this.cookieManager.getCookieStore().add(null, httpCookie);
                            }
                        }
                    }
                }
            }
        }
        if (!verifyLogin()) {
            throw new ConnectionException("Verify login failed after token exchange");
        }
        this.renewTime = (long) (System.currentTimeMillis() + Connection.EXPIRES_IN * 1000d / 0.8d); // start renew at
    }

    public boolean checkRenewSession()
            throws URISyntaxException, IOException, ExecutionException, InterruptedException {
        if (System.currentTimeMillis() >= this.renewTime) {
            String renewTokenPostData = "app_name=Amazon%20Alexa&app_version=2.2.223830.0&di.sdk.version=6.10.0&source_token="
                    + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8.name())
                    + "&package_name=com.amazon.echo&di.hw.version=iPhone&platform=iOS&requested_token_type=access_token&source_token_type=refresh_token&di.os.name=iOS&di.os.version=11.4.1&current_version=6.10.0";
            String renewTokenRepsonseJson = makeRequestAndReturnString("POST", "https://api.amazon.com/auth/token",
                    renewTokenPostData, false, null).get();
            parseJson(renewTokenRepsonseJson, JsonRenewTokenResponse.class);

            exchangeToken();
            return true;
        }
        return false;
    }

    public boolean getIsLoggedIn() {
        return loginTime != null;
    }

    public String getLoginPage() throws URISyntaxException, ExecutionException, InterruptedException {
        // clear session data
        logout();

        logger.debug("Start Login to {}", alexaServer);

        String mapMdJson = "{\"device_user_dictionary\":[],\"device_registration_data\":{\"software_version\":\"1\"},\"app_identifier\":{\"app_version\":\"2.2.223830\",\"bundle_id\":\"com.amazon.echo\"}}";
        String mapMdCookie = Base64.getEncoder().encodeToString(mapMdJson.getBytes());

        cookieManager.getCookieStore().add(new URI("https://www.amazon.com"), new HttpCookie("map-md", mapMdCookie));
        cookieManager.getCookieStore().add(new URI("https://www.amazon.com"), new HttpCookie("frc", frc));

        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("authority", "www.amazon.com");
        String loginFormHtml = makeRequestAndReturnString("GET", "https://www.amazon.com"
                + "/ap/signin?openid.return_to=https://www.amazon.com/ap/maplanding&openid.assoc_handle=amzn_dp_project_dee_ios&openid.identity=http://specs.openid.net/auth/2.0/identifier_select&pageId=amzn_dp_project_dee_ios&accountStatusPolicy=P1&openid.claimed_id=http://specs.openid.net/auth/2.0/identifier_select&openid.mode=checkid_setup&openid.ns.oa2=http://www.amazon.com/ap/ext/oauth/2&openid.oa2.client_id=device:"
                + deviceId
                + "&openid.ns.pape=http://specs.openid.net/extensions/pape/1.0&openid.oa2.response_type=token&openid.ns=http://specs.openid.net/auth/2.0&openid.pape.max_auth_age=0&openid.oa2.scope=device_auth_access",
                null, false, customHeaders).get();

        logger.debug("Received login form {}", loginFormHtml);
        return loginFormHtml;
    }

    public boolean verifyLogin() throws ExecutionException, InterruptedException {
        if (this.refreshToken == null) {
            return false;
        }
        Authentication authentication = tryGetBootstrap();
        if (authentication != null && authentication.authenticated) {
            verifyTime = new Date();
            if (loginTime == null) {
                loginTime = verifyTime;
            }
            return true;
        }
        return false;
    }

    public List<HttpCookie> getSessionCookies() {
        try {
            return cookieManager.getCookieStore().get(new URI(alexaServer));
        } catch (URISyntaxException e) {
            return new ArrayList<>();
        }
    }

    public List<HttpCookie> getSessionCookies(String server) {
        try {
            return cookieManager.getCookieStore().get(new URI(server));
        } catch (URISyntaxException e) {
            return new ArrayList<>();
        }
    }

    public void logout() {
        cookieManager.getCookieStore().removeAll();
        // reset all members
        refreshToken = null;
        loginTime = null;
        verifyTime = null;
        deviceName = null;

        if (announcementTimer != null) {
            announcements.clear();
            announcementTimer.cancel(true);
        }
        if (textToSpeechTimer != null) {
            textToSpeeches.clear();
            textToSpeechTimer.cancel(true);
        }
        if (announcementSenderUnblockFuture != null) {
            announcementQueue.clear();
            announcementQueueRunning.set(false);
            announcementSenderUnblockFuture.cancel(true);
        }
        if (textToSpeechSenderUnblockFuture != null) {
            textToSpeechQueue.clear();
            textToSpeechQueueRunning.set(false);
            textToSpeechSenderUnblockFuture.cancel(true);
        }
        if (sequenceNodeSenderUnblockFuture != null) {
            sequenceNodeQueue.clear();
            sequenceNodeQueueRunning.set(false);
            sequenceNodeSenderUnblockFuture.cancel(true);
        }
    }

    // parser
    private <T> @Nullable T parseJson(String json, Class<T> type) throws JsonSyntaxException, IllegalStateException {
        try {
            return gson.fromJson(json, type);
        } catch (JsonParseException | IllegalStateException e) {
            logger.warn("Parsing json failed", e);
            logger.warn("Illegal json: {}", json);
            throw e;
        }
    }

    // commands and states
    public WakeWord[] getWakeWords() {
        String json;
        try {
            json = makeRequestAndReturnString(alexaServer + "/api/wake-word?cached=true").get();
            JsonWakeWords wakeWords = parseJson(json, JsonWakeWords.class);
            if (wakeWords != null) {
                WakeWord[] result = wakeWords.wakeWords;
                if (result != null) {
                    return result;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("getting wakewords failed", e);
        }
        return new WakeWord[0];
    }

    public List<SmartHomeBaseDevice> getSmarthomeDeviceList() {
        try {
            String json = makeRequestAndReturnString(alexaServer + "/api/phoenix").get();
            logger.debug("getSmartHomeDevices result: {}", json);

            JsonNetworkDetails networkDetails = parseJson(json, JsonNetworkDetails.class);
            if (networkDetails == null) {
                throw new IllegalArgumentException("received no response on network detail request");
            }
            Object jsonObject = gson.fromJson(networkDetails.networkDetail, Object.class);
            List<SmartHomeBaseDevice> result = new ArrayList<>();
            searchSmartHomeDevicesRecursive(jsonObject, result);

            return result;
        } catch (Exception e) {
            logger.warn("getSmartHomeDevices fails: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void searchSmartHomeDevicesRecursive(@Nullable Object jsonNode, List<SmartHomeBaseDevice> devices) {
        if (jsonNode instanceof Map) {
            @SuppressWarnings("rawtypes")
            Map map = (Map) jsonNode;
            if (map.containsKey("entityId") && map.containsKey("friendlyName") && map.containsKey("actions")) {
                // device node found, create type element and add it to the results
                JsonElement element = gson.toJsonTree(jsonNode);
                SmartHomeDevice shd = parseJson(element.toString(), SmartHomeDevice.class);
                if (shd != null) {
                    devices.add(shd);
                }
            } else if (map.containsKey("applianceGroupName")) {
                JsonElement element = gson.toJsonTree(jsonNode);
                SmartHomeGroup shg = parseJson(element.toString(), SmartHomeGroup.class);
                if (shg != null) {
                    devices.add(shg);
                }
            } else {
                map.values().forEach(value -> searchSmartHomeDevicesRecursive(value, devices));
            }
        }
    }

    public List<Device> getDeviceList() throws ExecutionException, InterruptedException {
        String json = getDeviceListJson();
        JsonDevices devices = parseJson(json, JsonDevices.class);
        if (devices != null) {
            Device[] result = devices.devices;
            if (result != null) {
                return new ArrayList<>(Arrays.asList(result));
            }
        }
        return Collections.emptyList();
    }

    public String getDeviceListJson() throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(alexaServer + "/api/devices-v2/device?cached=false").get();
        return json;
    }

    public Map<String, JsonArray> getSmartHomeDeviceStatesJson(Set<String> applianceIds)
            throws ExecutionException, InterruptedException {
        JsonObject requestObject = new JsonObject();
        JsonArray stateRequests = new JsonArray();
        for (String applianceId : applianceIds) {
            JsonObject stateRequest = new JsonObject();
            stateRequest.addProperty("entityId", applianceId);
            stateRequest.addProperty("entityType", "APPLIANCE");
            stateRequests.add(stateRequest);
        }
        requestObject.add("stateRequests", stateRequests);
        String requestBody = requestObject.toString();
        String json = makeRequestAndReturnString("POST", alexaServer + "/api/phoenix/state", requestBody, true, null)
                .get();
        logger.trace("Requested {} and received {}", requestBody, json);

        JsonObject responseObject = this.gson.fromJson(json, JsonObject.class);
        JsonArray deviceStates = (JsonArray) responseObject.get("deviceStates");
        Map<String, JsonArray> result = new HashMap<>();
        for (JsonElement deviceState : deviceStates) {
            JsonObject deviceStateObject = deviceState.getAsJsonObject();
            JsonObject entity = deviceStateObject.get("entity").getAsJsonObject();
            String applicanceId = entity.get("entityId").getAsString();
            JsonElement capabilityState = deviceStateObject.get("capabilityStates");
            if (capabilityState != null && capabilityState.isJsonArray()) {
                result.put(applicanceId, capabilityState.getAsJsonArray());
            }
        }
        return result;
    }

    public @Nullable JsonPlayerState getPlayer(Device device) throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(alexaServer + "/api/np/player?deviceSerialNumber="
                + device.serialNumber + "&deviceType=" + device.deviceType + "&screenWidth=1440").get();
        JsonPlayerState playerState = parseJson(json, JsonPlayerState.class);
        return playerState;
    }

    public @Nullable JsonMediaState getMediaState(Device device) throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(alexaServer + "/api/media/state?deviceSerialNumber="
                + device.serialNumber + "&deviceType=" + device.deviceType).get();
        JsonMediaState mediaState = parseJson(json, JsonMediaState.class);
        return mediaState;
    }

    public Activity[] getActivities(int number, @Nullable Long startTime) {
        String json;
        try {
            json = makeRequestAndReturnString(alexaServer + "/api/activities?startTime="
                    + (startTime != null ? startTime : "") + "&size=" + number + "&offset=1").get();
            JsonActivities activities = parseJson(json, JsonActivities.class);
            if (activities != null) {
                Activity[] activiesArray = activities.activities;
                if (activiesArray != null) {
                    return activiesArray;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("getting activities failed", e);
        }
        return new Activity[0];
    }

    public @Nullable JsonBluetoothStates getBluetoothConnectionStates() {
        String json;
        try {
            json = makeRequestAndReturnString(alexaServer + "/api/bluetooth?cached=true").get();
        } catch (InterruptedException | ExecutionException e) {
            logger.debug("failed to get bluetooth state: {}", e.getMessage());
            return new JsonBluetoothStates();
        }
        JsonBluetoothStates bluetoothStates = parseJson(json, JsonBluetoothStates.class);
        return bluetoothStates;
    }

    public @Nullable JsonPlaylists getPlaylists(Device device) throws InterruptedException, ExecutionException {
        String json = makeRequestAndReturnString(alexaServer + "/api/cloudplayer/playlists?deviceSerialNumber="
                + device.serialNumber + "&deviceType=" + device.deviceType + "&mediaOwnerCustomerId="
                + (this.accountCustomerId == null || this.accountCustomerId.isEmpty() ? device.deviceOwnerCustomerId
                        : this.accountCustomerId)).get();
        JsonPlaylists playlists = parseJson(json, JsonPlaylists.class);
        return playlists;
    }

    public void command(Device device, String command) throws IOException, URISyntaxException {
        String url = alexaServer + "/api/np/command?deviceSerialNumber=" + device.serialNumber + "&deviceType="
                + device.deviceType;
        makeRequest("POST", url, command, true, true, null, 0);
    }

    public void smartHomeCommand(String entityId, String action) throws IOException {
        smartHomeCommand(entityId, action, null, null);
    }

    public void smartHomeCommand(String entityId, String action, @Nullable String property, @Nullable Object value)
            throws IOException {
        String url = alexaServer + "/api/phoenix/state";

        JsonObject json = new JsonObject();
        JsonArray controlRequests = new JsonArray();
        JsonObject controlRequest = new JsonObject();
        controlRequest.addProperty("entityId", entityId);
        controlRequest.addProperty("entityType", "APPLIANCE");
        JsonObject parameters = new JsonObject();
        parameters.addProperty("action", action);
        if (property != null) {
            if (value instanceof Boolean) {
                parameters.addProperty(property, (boolean) value);
            } else if (value instanceof String) {
                parameters.addProperty(property, (String) value);
            } else if (value instanceof Number) {
                parameters.addProperty(property, (Number) value);
            } else if (value instanceof Character) {
                parameters.addProperty(property, (Character) value);
            } else if (value instanceof JsonElement) {
                parameters.add(property, (JsonElement) value);
            }
        }
        controlRequest.add("parameters", parameters);
        controlRequests.add(controlRequest);
        json.add("controlRequests", controlRequests);

        String requestBody = json.toString();
        try {
            String resultBody = makeRequestAndReturnString("PUT", url, requestBody, true, null).get();
            logger.debug("{}", resultBody);
            JsonObject result = parseJson(resultBody, JsonObject.class);
            if (result != null) {
                JsonElement errors = result.get("errors");
                if (errors != null && errors.isJsonArray()) {
                    JsonArray errorList = errors.getAsJsonArray();
                    if (errorList.size() > 0) {
                        logger.info("Smart home device command failed.");
                        logger.info("Request:");
                        logger.info("{}", requestBody);
                        logger.info("Answer:");
                        for (JsonElement error : errorList) {
                            logger.info("{}", error.toString());
                        }
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("Request failed", e);
        }
    }

    public void notificationVolume(Device device, int volume) throws IOException, URISyntaxException {
        String url = alexaServer + "/api/device-notification-state/" + device.deviceType + "/" + device.softwareVersion
                + "/" + device.serialNumber;
        String command = "{\"deviceSerialNumber\":\"" + device.serialNumber + "\",\"deviceType\":\"" + device.deviceType
                + "\",\"softwareVersion\":\"" + device.softwareVersion + "\",\"volumeLevel\":" + volume + "}";
        makeRequest("PUT", url, command, true, true, null, 0);
    }

    public void ascendingAlarm(Device device, boolean ascendingAlarm) throws IOException, URISyntaxException {
        String url = alexaServer + "/api/ascending-alarm/" + device.serialNumber;
        String command = "{\"ascendingAlarmEnabled\":" + (ascendingAlarm ? "true" : "false")
                + ",\"deviceSerialNumber\":\"" + device.serialNumber + "\",\"deviceType\":\"" + device.deviceType
                + "\",\"deviceAccountId\":null}";
        makeRequest("PUT", url, command, true, true, null, 0);
    }

    public DeviceNotificationState[] getDeviceNotificationStates() {
        String json;
        try {
            json = makeRequestAndReturnString(alexaServer + "/api/device-notification-state").get();
            JsonDeviceNotificationState result = parseJson(json, JsonDeviceNotificationState.class);
            if (result != null) {
                DeviceNotificationState[] deviceNotificationStates = result.deviceNotificationStates;
                if (deviceNotificationStates != null) {
                    return deviceNotificationStates;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("Error getting device notification states", e);
        }
        return new DeviceNotificationState[0];
    }

    public AscendingAlarmModel[] getAscendingAlarm() {
        String json;
        try {
            json = makeRequestAndReturnString(alexaServer + "/api/ascending-alarm").get();
            JsonAscendingAlarm result = parseJson(json, JsonAscendingAlarm.class);
            if (result != null) {
                AscendingAlarmModel[] ascendingAlarmModelList = result.ascendingAlarmModelList;
                if (ascendingAlarmModelList != null) {
                    return ascendingAlarmModelList;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("Error getting device notification states", e);
        }
        return new AscendingAlarmModel[0];
    }

    public void bluetooth(Device device, @Nullable String address) throws IOException, URISyntaxException {
        if (address == null || address.isEmpty()) {
            // disconnect
            makeRequest("POST",
                    alexaServer + "/api/bluetooth/disconnect-sink/" + device.deviceType + "/" + device.serialNumber, "",
                    true, true, null, 0);
        } else {
            makeRequest("POST",
                    alexaServer + "/api/bluetooth/pair-sink/" + device.deviceType + "/" + device.serialNumber,
                    "{\"bluetoothDeviceAddress\":\"" + address + "\"}", true, true, null, 0);
        }
    }

    public void playRadio(Device device, @Nullable String stationId) throws IOException, URISyntaxException {
        if (stationId == null || stationId.isEmpty()) {
            command(device, "{\"type\":\"PauseCommand\"}");
        } else {
            makeRequest("POST",
                    alexaServer + "/api/tunein/queue-and-play?deviceSerialNumber=" + device.serialNumber
                            + "&deviceType=" + device.deviceType + "&guideId=" + stationId
                            + "&contentType=station&callSign=&mediaOwnerCustomerId="
                            + (this.accountCustomerId == null || this.accountCustomerId.isEmpty()
                                    ? device.deviceOwnerCustomerId
                                    : this.accountCustomerId),
                    "", true, true, null, 0);
        }
    }

    public void playAmazonMusicTrack(Device device, @Nullable String trackId) throws IOException, URISyntaxException {
        if (trackId == null || trackId.isEmpty()) {
            command(device, "{\"type\":\"PauseCommand\"}");
        } else {
            String command = "{\"trackId\":\"" + trackId + "\",\"playQueuePrime\":true}";
            makeRequest("POST",
                    alexaServer + "/api/cloudplayer/queue-and-play?deviceSerialNumber=" + device.serialNumber
                            + "&deviceType=" + device.deviceType + "&mediaOwnerCustomerId="
                            + (this.accountCustomerId == null || this.accountCustomerId.isEmpty()
                                    ? device.deviceOwnerCustomerId
                                    : this.accountCustomerId)
                            + "&shuffle=false",
                    command, true, true, null, 0);
        }
    }

    public void playAmazonMusicPlayList(Device device, @Nullable String playListId)
            throws IOException, URISyntaxException {
        if (playListId == null || playListId.isEmpty()) {
            command(device, "{\"type\":\"PauseCommand\"}");
        } else {
            String command = "{\"playlistId\":\"" + playListId + "\",\"playQueuePrime\":true}";
            makeRequest("POST",
                    alexaServer + "/api/cloudplayer/queue-and-play?deviceSerialNumber=" + device.serialNumber
                            + "&deviceType=" + device.deviceType + "&mediaOwnerCustomerId="
                            + (this.accountCustomerId == null || this.accountCustomerId.isEmpty()
                                    ? device.deviceOwnerCustomerId
                                    : this.accountCustomerId)
                            + "&shuffle=false",
                    command, true, true, null, 0);
        }
    }

    public void sendNotificationToMobileApp(String customerId, String text, @Nullable String title)
            throws IOException, URISyntaxException {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("notificationMessage", text);
        parameters.put("alexaUrl", "#v2/behaviors");
        if (title != null && !title.isEmpty()) {
            parameters.put("title", title);
        } else {
            parameters.put("title", "OpenHAB");
        }
        parameters.put("customerId", customerId);
        executeSequenceCommand(null, "Alexa.Notifications.SendMobilePush", parameters);
    }

    public synchronized void announcement(Device device, String speak, String bodyText, @Nullable String title,
            @Nullable Integer ttsVolume, @Nullable Integer standardVolume) {
        if (speak == null || speak.replaceAll("<.+?>", "").trim().isEmpty()) {
            return;
        }
        if (announcementTimer != null) {
            announcementTimer.cancel(true);
        }
        Announcement announcement = announcements.computeIfAbsent(Objects.hash(speak, bodyText, title),
                k -> new Announcement(speak, bodyText, title));
        announcement.devices.add(device);
        announcement.ttsVolumes.add(ttsVolume);
        announcement.standardVolumes.add(standardVolume);
        announcementTimer = scheduler.schedule(this::sendAnnouncement, 500, TimeUnit.MILLISECONDS);
    }

    private void sendAnnouncement() {
        if (announcementTimer != null) {
            announcementTimer.cancel(true);
        }
        announcementQueue.addAll(announcements.values());
        announcements.clear();
        if (announcementQueueRunning.compareAndSet(false, true)) {
            queuedAnnouncement();
        }
    }

    private void queuedAnnouncement() {
        Announcement announcement = announcementQueue.poll();
        if (announcement != null) {
            try {
                List<Device> devices = announcement.devices;
                if (devices != null && !devices.isEmpty()) {
                    String speak = announcement.speak;
                    String bodyText = announcement.bodyText;
                    String title = announcement.title;
                    List<@Nullable Integer> ttsVolumes = announcement.ttsVolumes;
                    List<@Nullable Integer> standardVolumes = announcement.standardVolumes;

                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("expireAfter", "PT5S");
                    JsonAnnouncementContent[] contentArray = new JsonAnnouncementContent[1];
                    JsonAnnouncementContent content = new JsonAnnouncementContent();
                    content.display.title = title == null || title.isEmpty() ? "openHAB" : title;
                    content.display.body = bodyText;
                    content.display.body = speak.replaceAll("<.+?>", "");
                    if (speak.startsWith("<speak>") && speak.endsWith("</speak>")) {
                        content.speak.type = "ssml";
                    }
                    content.speak.value = speak;

                    contentArray[0] = content;

                    parameters.put("content", contentArray);

                    JsonAnnouncementTarget target = new JsonAnnouncementTarget();
                    target.customerId = devices.get(0).deviceOwnerCustomerId;
                    TargetDevice[] targetDevices = devices.stream().map(TargetDevice::new).collect(Collectors.toList())
                            .toArray(new TargetDevice[0]);
                    target.devices = targetDevices;
                    parameters.put("target", target);

                    String accountCustomerId = this.accountCustomerId;
                    String customerId = accountCustomerId == null || accountCustomerId.isEmpty()
                            ? devices.toArray(new Device[0])[0].deviceOwnerCustomerId
                            : accountCustomerId;

                    if (customerId != null) {
                        parameters.put("customerId", customerId);
                    }
                    executeSequenceCommandWithVolume(devices.toArray(new Device[0]), "AlexaAnnouncement", parameters,
                            ttsVolumes.toArray(new Integer[0]), standardVolumes.toArray(new Integer[0]));
                }
            } catch (IOException | URISyntaxException e) {
                logger.warn("send textToSpeech fails with unexpected error", e);
            } finally {
                announcementSenderUnblockFuture = scheduler.schedule(this::queuedAnnouncement, 1000,
                        TimeUnit.MILLISECONDS);
            }
        } else {
            announcementQueueRunning.set(false);
            announcementSenderUnblockFuture = null;
        }
    }

    public synchronized void textToSpeech(Device device, String text, @Nullable Integer ttsVolume,
            @Nullable Integer standardVolume) {
        if (text == null || text.replaceAll("<.+?>", "").trim().isEmpty()) {
            return;
        }
        if (textToSpeechTimer != null) {
            textToSpeechTimer.cancel(true);
        }
        TextToSpeech textToSpeech = textToSpeeches.computeIfAbsent(Objects.hash(text), k -> new TextToSpeech(text));
        textToSpeech.devices.add(device);
        textToSpeech.ttsVolumes.add(ttsVolume);
        textToSpeech.standardVolumes.add(standardVolume);
        textToSpeechTimer = scheduler.schedule(this::sendTextToSpeech, 500, TimeUnit.MILLISECONDS);
    }

    private void sendTextToSpeech() {
        if (textToSpeechTimer != null) {
            textToSpeechTimer.cancel(true);
        }
        textToSpeechQueue.addAll(textToSpeeches.values());
        textToSpeeches.clear();

        if (textToSpeechQueueRunning.compareAndSet(false, true)) {
            queuedTextToSpeech();
        }
    }

    private void queuedTextToSpeech() {
        TextToSpeech textToSpeech = textToSpeechQueue.poll();
        if (textToSpeech != null) {
            try {
                List<Device> devices = textToSpeech.devices;
                if (devices != null && !devices.isEmpty()) {
                    String text = textToSpeech.text;
                    List<@Nullable Integer> ttsVolumes = textToSpeech.ttsVolumes;
                    List<@Nullable Integer> standardVolumes = textToSpeech.standardVolumes;

                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("textToSpeak", text);
                    executeSequenceCommandWithVolume(devices.toArray(new Device[0]), "Alexa.Speak", parameters,
                            ttsVolumes.toArray(new Integer[0]), standardVolumes.toArray(new Integer[0]));
                }
            } catch (IOException | URISyntaxException e) {
                logger.warn("send textToSpeech fails with unexpected error", e);
            } finally {
                textToSpeechSenderUnblockFuture = scheduler.schedule(this::queuedTextToSpeech, 1000,
                        TimeUnit.MILLISECONDS);
            }
        } else {
            textToSpeechQueueRunning.set(false);
            textToSpeechSenderUnblockFuture = null;
        }
    }

    private void executeSequenceCommandWithVolume(@Nullable Device[] devices, String command,
            @Nullable Map<String, Object> parameters, @NonNull Integer[] ttsVolumes, @NonNull Integer[] standardVolumes)
            throws IOException, URISyntaxException {
        JsonArray ttsVolumeNodesToExecute = new JsonArray();
        for (int i = 0; i < devices.length; i++) {
            if (ttsVolumes[i] != null && standardVolumes[i] != null && !ttsVolumes[i].equals(standardVolumes[i])) {
                Map<String, Object> volumeParameters = new HashMap<>();
                volumeParameters.put("value", ttsVolumes[i]);
                ttsVolumeNodesToExecute
                        .add(createExecutionNode(devices[i], "Alexa.DeviceControls.Volume", volumeParameters));
            }
        }
        if (ttsVolumeNodesToExecute.size() > 0) {
            executeSequenceNodes(ttsVolumeNodesToExecute, true);
        }

        JsonArray nodesToExecute = new JsonArray();
        if ("Alexa.Speak".equals(command)) {
            for (Device device : devices) {
                nodesToExecute.add(createExecutionNode(device, command, parameters));
            }
        } else {
            nodesToExecute.add(createExecutionNode(devices[0], command, parameters));
        }
        if (nodesToExecute.size() > 0) {
            executeSequenceNodes(nodesToExecute, true);
        }

        JsonArray standardVolumeNodesToExecute = new JsonArray();
        for (int i = 0; i < devices.length; i++) {
            final Device device = devices[i];
            if (textToSpeechQueue.stream().noneMatch(t -> t.devices.contains(device))
                    && announcementQueue.stream().noneMatch(t -> t.devices.contains(device))) {
                if (ttsVolumes[i] != null && standardVolumes[i] != null && !ttsVolumes[i].equals(standardVolumes[i])) {
                    Map<String, Object> volumeParameters = new HashMap<>();
                    volumeParameters.put("value", standardVolumes[i]);
                    standardVolumeNodesToExecute
                            .add(createExecutionNode(devices[i], "Alexa.DeviceControls.Volume", volumeParameters));
                }
            }
        }
        if (standardVolumeNodesToExecute.size() > 0) {
            executeSequenceNodes(standardVolumeNodesToExecute, true);
        }
    }

    // commands: Alexa.Weather.Play, Alexa.Traffic.Play, Alexa.FlashBriefing.Play,
    // Alexa.GoodMorning.Play,
    // Alexa.SingASong.Play, Alexa.TellStory.Play, Alexa.Speak (textToSpeach)
    public void executeSequenceCommand(@Nullable Device device, String command,
            @Nullable Map<String, Object> parameters) throws IOException, URISyntaxException {
        JsonObject nodeToExecute = createExecutionNode(device, command, parameters);
        executeSequenceNode(nodeToExecute);
    }

    private void executeSequenceNode(JsonObject nodeToExecute) {
        sequenceNodeQueue.add(nodeToExecute);
        if (sequenceNodeQueueRunning.compareAndSet(false, true)) {
            queuedExecuteSequenceNode();
        }
    }

    private void queuedExecuteSequenceNode() {
        JsonObject nodeToExecute = sequenceNodeQueue.poll();
        if (nodeToExecute != null) {
            long delay = 2000;
            try {
                JsonObject sequenceJson = new JsonObject();
                sequenceJson.addProperty("@type", "com.amazon.alexa.behaviors.model.Sequence");
                sequenceJson.add("startNode", nodeToExecute);

                JsonStartRoutineRequest request = new JsonStartRoutineRequest();
                request.sequenceJson = gson.toJson(sequenceJson);
                String json = gson.toJson(request);

                Map<String, String> headers = new HashMap<>();
                headers.put("Routines-Version", "1.1.218665");

                makeRequest("POST", alexaServer + "/api/behaviors/preview", json, true, true, null, 3);

                String text = getTextFromExecutionNode(nodeToExecute);
                if (text != null && !text.isEmpty()) {
                    delay += text.length() * 100;
                }
            } finally {
                sequenceNodeSenderUnblockFuture = scheduler.schedule(this::queuedExecuteSequenceNode, delay,
                        TimeUnit.MILLISECONDS);
            }
        } else {
            sequenceNodeQueueRunning.set(false);
            sequenceNodeSenderUnblockFuture = null;
        }
    }

    private void executeSequenceNodes(JsonArray nodesToExecute, boolean parallel)
            throws IOException, URISyntaxException {
        JsonObject serialNode = new JsonObject();
        if (parallel) {
            serialNode.addProperty("@type", "com.amazon.alexa.behaviors.model.ParallelNode");
        } else {
            serialNode.addProperty("@type", "com.amazon.alexa.behaviors.model.SerialNode");
        }

        serialNode.add("nodesToExecute", nodesToExecute);

        executeSequenceNode(serialNode);
    }

    private JsonObject createExecutionNode(@Nullable Device device, String command,
            @Nullable Map<String, Object> parameters) {
        JsonObject operationPayload = new JsonObject();
        if (device != null) {
            operationPayload.addProperty("deviceType", device.deviceType);
            operationPayload.addProperty("deviceSerialNumber", device.serialNumber);
            operationPayload.addProperty("locale", "");
            operationPayload.addProperty("customerId",
                    this.accountCustomerId == null || this.accountCustomerId.isEmpty() ? device.deviceOwnerCustomerId
                            : this.accountCustomerId);
        }
        if (parameters != null) {
            for (String key : parameters.keySet()) {
                Object value = parameters.get(key);
                if (value instanceof String) {
                    operationPayload.addProperty(key, (String) value);
                } else if (value instanceof Number) {
                    operationPayload.addProperty(key, (Number) value);
                } else if (value instanceof Boolean) {
                    operationPayload.addProperty(key, (Boolean) value);
                } else if (value instanceof Character) {
                    operationPayload.addProperty(key, (Character) value);
                } else {
                    operationPayload.add(key, gson.toJsonTree(value));
                }
            }
        }

        JsonObject nodeToExecute = new JsonObject();
        nodeToExecute.addProperty("@type", "com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode");
        nodeToExecute.addProperty("type", command);
        nodeToExecute.add("operationPayload", operationPayload);
        return nodeToExecute;
    }

    @Nullable
    private String getTextFromExecutionNode(JsonObject nodeToExecute) {
        if (nodeToExecute.has("nodesToExecute")) {
            JsonArray nodesToExecute = nodeToExecute.getAsJsonArray("nodesToExecute");
            if (nodesToExecute != null && nodesToExecute.size() > 0) {
                JsonObject nodesToExecuteJsonObject = nodesToExecute.get(0).getAsJsonObject();
                if (nodesToExecuteJsonObject != null && nodesToExecuteJsonObject.has("operationPayload")) {
                    JsonObject operationPayload = nodesToExecuteJsonObject.getAsJsonObject("operationPayload");
                    if (operationPayload != null) {
                        if (operationPayload.has("textToSpeak")) {
                            return operationPayload.get("textToSpeak").getAsString();
                        } else if (operationPayload.has("content")) {
                            JsonArray content = operationPayload.getAsJsonArray("content");
                            if (content != null && content.size() > 0) {
                                JsonObject contentJsonObject = content.get(0).getAsJsonObject();
                                if (contentJsonObject != null && contentJsonObject.has("speak")) {
                                    JsonObject speak = contentJsonObject.getAsJsonObject("speak");
                                    if (speak != null && speak.has("value")) {
                                        return speak.get("value").getAsString();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    public void startRoutine(Device device, String utterance) throws ExecutionException, InterruptedException {
        JsonAutomation found = null;
        String deviceLocale = "";
        JsonAutomation[] routines = getRoutines();
        if (routines == null) {
            return;
        }
        for (JsonAutomation routine : getRoutines()) {
            if (routine != null) {
                Trigger[] triggers = routine.triggers;
                if (triggers != null && routine.sequence != null) {
                    for (JsonAutomation.Trigger trigger : triggers) {
                        if (trigger == null) {
                            continue;
                        }
                        Payload payload = trigger.payload;
                        if (payload == null) {
                            continue;
                        }
                        if (payload.utterance != null && payload.utterance.equalsIgnoreCase(utterance)) {
                            found = routine;
                            deviceLocale = payload.locale;
                            break;
                        }
                    }
                }
            }
        }
        if (found != null) {
            String sequenceJson = gson.toJson(found.sequence);

            JsonStartRoutineRequest request = new JsonStartRoutineRequest();
            request.behaviorId = found.automationId;

            // replace tokens
            // "deviceType":"ALEXA_CURRENT_DEVICE_TYPE"
            String deviceType = "\"deviceType\":\"ALEXA_CURRENT_DEVICE_TYPE\"";
            String newDeviceType = "\"deviceType\":\"" + device.deviceType + "\"";
            sequenceJson = sequenceJson.replace(deviceType.subSequence(0, deviceType.length()),
                    newDeviceType.subSequence(0, newDeviceType.length()));

            // "deviceSerialNumber":"ALEXA_CURRENT_DSN"
            String deviceSerial = "\"deviceSerialNumber\":\"ALEXA_CURRENT_DSN\"";
            String newDeviceSerial = "\"deviceSerialNumber\":\"" + device.serialNumber + "\"";
            sequenceJson = sequenceJson.replace(deviceSerial.subSequence(0, deviceSerial.length()),
                    newDeviceSerial.subSequence(0, newDeviceSerial.length()));

            // "customerId": "ALEXA_CUSTOMER_ID"
            String customerId = "\"customerId\":\"ALEXA_CUSTOMER_ID\"";
            String newCustomerId = "\"customerId\":\""
                    + (this.accountCustomerId == null || this.accountCustomerId.isEmpty() ? device.deviceOwnerCustomerId
                            : this.accountCustomerId)
                    + "\"";
            sequenceJson = sequenceJson.replace(customerId.subSequence(0, customerId.length()),
                    newCustomerId.subSequence(0, newCustomerId.length()));

            // "locale": "ALEXA_CURRENT_LOCALE"
            String locale = "\"locale\":\"ALEXA_CURRENT_LOCALE\"";
            String newlocale = deviceLocale != null && !deviceLocale.isEmpty() ? "\"locale\":\"" + deviceLocale + "\""
                    : "\"locale\":null";
            sequenceJson = sequenceJson.replace(locale.subSequence(0, locale.length()),
                    newlocale.subSequence(0, newlocale.length()));

            request.sequenceJson = sequenceJson;

            String requestJson = gson.toJson(request);
            makeRequest("POST", alexaServer + "/api/behaviors/preview", requestJson, true, true, null, 3);
        } else {
            logger.warn("Routine {} not found", utterance);
        }
    }

    public @Nullable JsonAutomation @Nullable [] getRoutines() throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(alexaServer + "/api/behaviors/automations?limit=2000").get();
        JsonAutomation[] result = parseJson(json, JsonAutomation[].class);
        return result;
    }

    public JsonFeed[] getEnabledFlashBriefings() throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(alexaServer + "/api/content-skills/enabled-feeds").get();
        JsonEnabledFeeds result = parseJson(json, JsonEnabledFeeds.class);
        if (result == null) {
            return new JsonFeed[0];
        }
        JsonFeed[] enabledFeeds = result.enabledFeeds;
        if (enabledFeeds != null) {
            return enabledFeeds;
        }
        return new JsonFeed[0];
    }

    public void setEnabledFlashBriefings(JsonFeed[] enabledFlashBriefing) {
        JsonEnabledFeeds enabled = new JsonEnabledFeeds();
        enabled.enabledFeeds = enabledFlashBriefing;
        String json = gsonWithNullSerialization.toJson(enabled);
        makeRequest("POST", alexaServer + "/api/content-skills/enabled-feeds", json, true, true, null, 0);
    }

    public JsonNotificationSound[] getNotificationSounds(Device device)
            throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(
                alexaServer + "/api/notification/sounds?deviceSerialNumber=" + device.serialNumber + "&deviceType="
                        + device.deviceType + "&softwareVersion=" + device.softwareVersion).get();
        JsonNotificationSounds result = parseJson(json, JsonNotificationSounds.class);
        if (result == null) {
            return new JsonNotificationSound[0];
        }
        JsonNotificationSound[] notificationSounds = result.notificationSounds;
        if (notificationSounds != null) {
            return notificationSounds;
        }
        return new JsonNotificationSound[0];
    }

    public JsonNotificationResponse[] notifications() throws ExecutionException, InterruptedException {
        String response = makeRequestAndReturnString(alexaServer + "/api/notifications").get();
        JsonNotificationsResponse result = parseJson(response, JsonNotificationsResponse.class);
        if (result == null) {
            return new JsonNotificationResponse[0];
        }
        JsonNotificationResponse[] notifications = result.notifications;
        if (notifications == null) {
            return new JsonNotificationResponse[0];
        }
        return notifications;
    }

    public @Nullable JsonNotificationResponse notification(Device device, String type, @Nullable String label,
            @Nullable JsonNotificationSound sound) throws ExecutionException, InterruptedException {
        Date date = new Date(new Date().getTime());
        long createdDate = date.getTime();
        Date alarm = new Date(createdDate + 5000); // add 5 seconds, because amazon does not except calls for times in
        // the past (compared with the server time)
        long alarmTime = alarm.getTime();

        JsonNotificationRequest request = new JsonNotificationRequest();
        request.type = type;
        request.deviceSerialNumber = device.serialNumber;
        request.deviceType = device.deviceType;
        request.createdDate = createdDate;
        request.alarmTime = alarmTime;
        request.reminderLabel = label;
        request.sound = sound;
        request.originalDate = new SimpleDateFormat("yyyy-MM-dd").format(alarm);
        request.originalTime = new SimpleDateFormat("HH:mm:ss.SSSS").format(alarm);
        request.type = type;
        request.id = "create" + type;

        String data = gsonWithNullSerialization.toJson(request);
        String response = makeRequestAndReturnString("PUT", alexaServer + "/api/notifications/createReminder", data,
                true, null).get();
        JsonNotificationResponse result = parseJson(response, JsonNotificationResponse.class);
        return result;
    }

    public void stopNotification(JsonNotificationResponse notification) throws IOException, URISyntaxException {
        makeRequestAndReturnString("DELETE", alexaServer + "/api/notifications/" + notification.id, null, true, null);
    }

    public @Nullable JsonNotificationResponse getNotificationState(JsonNotificationResponse notification)
            throws ExecutionException, InterruptedException {
        String response = makeRequestAndReturnString("GET", alexaServer + "/api/notifications/" + notification.id, null,
                true, null).get();
        JsonNotificationResponse result = parseJson(response, JsonNotificationResponse.class);
        return result;
    }

    public List<JsonMusicProvider> getMusicProviders() {
        String response;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Routines-Version", "1.1.218665");
            response = makeRequestAndReturnString("GET",
                    alexaServer + "/api/behaviors/entities?skillId=amzn1.ask.1p.music", null, true, headers).get();
            if (response != null || !response.isEmpty()) {
                JsonMusicProvider[] result = parseJson(response, JsonMusicProvider[].class);
                return Arrays.asList(result);
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.warn("getMusicProviders fails: {}", e.getMessage());

        }
        return Collections.emptyList();
    }

    public void playMusicVoiceCommand(Device device, String providerId, String voiceCommand)
            throws ExecutionException, InterruptedException {
        JsonPlaySearchPhraseOperationPayload payload = new JsonPlaySearchPhraseOperationPayload();
        payload.customerId = (this.accountCustomerId == null || this.accountCustomerId.isEmpty()
                ? device.deviceOwnerCustomerId
                : this.accountCustomerId);
        payload.locale = "ALEXA_CURRENT_LOCALE";
        payload.musicProviderId = providerId;
        payload.searchPhrase = voiceCommand;

        String playloadString = gson.toJson(payload);

        JsonObject postValidationJson = new JsonObject();

        postValidationJson.addProperty("type", "Alexa.Music.PlaySearchPhrase");
        postValidationJson.addProperty("operationPayload", playloadString);

        String postDataValidate = postValidationJson.toString();

        String validateResultJson = makeRequestAndReturnString("POST",
                alexaServer + "/api/behaviors/operation/validate", postDataValidate, true, null).get();

        if (validateResultJson != null && !validateResultJson.isEmpty()) {
            JsonPlayValidationResult validationResult = parseJson(validateResultJson, JsonPlayValidationResult.class);
            if (validationResult != null) {
                JsonPlaySearchPhraseOperationPayload validatedOperationPayload = validationResult.operationPayload;
                if (validatedOperationPayload != null) {
                    payload.sanitizedSearchPhrase = validatedOperationPayload.sanitizedSearchPhrase;
                    payload.searchPhrase = validatedOperationPayload.searchPhrase;
                }
            }
        }

        payload.locale = null;
        payload.deviceSerialNumber = device.serialNumber;
        payload.deviceType = device.deviceType;

        JsonObject sequenceJson = new JsonObject();
        sequenceJson.addProperty("@type", "com.amazon.alexa.behaviors.model.Sequence");
        JsonObject startNodeJson = new JsonObject();
        startNodeJson.addProperty("@type", "com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode");
        startNodeJson.addProperty("type", "Alexa.Music.PlaySearchPhrase");
        startNodeJson.add("operationPayload", gson.toJsonTree(payload));
        sequenceJson.add("startNode", startNodeJson);

        JsonStartRoutineRequest startRoutineRequest = new JsonStartRoutineRequest();
        startRoutineRequest.sequenceJson = sequenceJson.toString();
        startRoutineRequest.status = null;

        String postData = gson.toJson(startRoutineRequest);
        makeRequest("POST", alexaServer + "/api/behaviors/preview", postData, true, true, null, 3);
    }

    public @Nullable JsonEqualizer getEqualizer(Device device) throws ExecutionException, InterruptedException {
        String json = makeRequestAndReturnString(
                alexaServer + "/api/equalizer/" + device.serialNumber + "/" + device.deviceType).get();
        return parseJson(json, JsonEqualizer.class);
    }

    public void setEqualizer(Device device, JsonEqualizer settings) throws IOException, URISyntaxException {
        String postData = gson.toJson(settings);
        makeRequest("POST", alexaServer + "/api/equalizer/" + device.serialNumber + "/" + device.deviceType, postData,
                true, true, null, 0);
    }

    @NonNullByDefault
    private static class Announcement {
        public List<Device> devices = new ArrayList<>();
        public String speak;
        public String bodyText;
        public @Nullable String title;
        public List<@Nullable Integer> ttsVolumes = new ArrayList<>();
        public List<@Nullable Integer> standardVolumes = new ArrayList<>();

        public Announcement(String speak, String bodyText, @Nullable String title) {
            this.speak = speak;
            this.bodyText = bodyText;
            this.title = title;
        }
    }

    @NonNullByDefault
    private static class TextToSpeech {
        public List<Device> devices = new ArrayList<>();
        public String text;
        public List<@Nullable Integer> ttsVolumes = new ArrayList<>();
        public List<@Nullable Integer> standardVolumes = new ArrayList<>();

        public TextToSpeech(String text) {
            this.text = text;
        }
    }
}
