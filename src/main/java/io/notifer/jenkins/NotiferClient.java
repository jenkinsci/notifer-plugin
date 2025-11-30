package io.notifer.jenkins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for communicating with Notifer API.
 * Uses Jenkins ProxyConfiguration to support corporate proxies.
 */
public class NotiferClient implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(NotiferClient.class.getName());
    private static final int TIMEOUT_SECONDS = 30;
    private static final Gson GSON = new GsonBuilder().create();

    /** Notifer API base URL */
    public static final String API_URL = "https://app.notifer.io";

    private final String token;

    /**
     * Create a new Notifer client.
     *
     * @param token Topic access token with write permission
     */
    public NotiferClient(String token) {
        this.token = token;
    }

    /**
     * Send a notification to a topic.
     *
     * @param topic    Topic name
     * @param message  Message content
     * @param title    Optional title (can be null)
     * @param priority Priority 1-5 (default 3)
     * @param tags     Optional list of tags (can be null or empty)
     * @return Response from the server
     * @throws NotiferException if the request fails
     */
    public NotiferResponse send(String topic, String message, String title, int priority, List<String> tags)
            throws NotiferException {

        String url = API_URL + "/" + topic;
        Map<String, Object> payload = buildPayload(message, title, priority, tags);

        LOGGER.log(Level.FINE, "Sending notification to {0}", url);

        try {
            HttpClient client = getHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("X-Topic-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode >= 200 && statusCode < 300) {
                LOGGER.log(Level.FINE, "Notification sent successfully: {0}", responseBody);
                return GSON.fromJson(responseBody, NotiferResponse.class);
            } else {
                String errorMessage = String.format("Notifer API returned status %d: %s", statusCode, responseBody);
                LOGGER.log(Level.WARNING, errorMessage);
                throw new NotiferException(errorMessage, statusCode);
            }

        } catch (IOException | InterruptedException e) {
            String errorMessage = "Failed to send notification: " + e.getMessage();
            LOGGER.log(Level.SEVERE, errorMessage, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NotiferException(errorMessage, e);
        }
    }

    /**
     * Get an HTTP client configured with Jenkins proxy settings if available.
     */
    private HttpClient getHttpClient() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            ProxyConfiguration proxy = jenkins.proxy;
            if (proxy != null) {
                // Use Jenkins proxy-aware HTTP client
                return proxy.newHttpClient();
            }
        }
        // Fallback to default client if no proxy configured
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private Map<String, Object> buildPayload(String message, String title, int priority, List<String> tags) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);

        if (title != null && !title.isEmpty()) {
            payload.put("title", title);
        }

        // Clamp priority to valid range
        priority = Math.max(1, Math.min(5, priority));
        payload.put("priority", priority);

        if (tags != null && !tags.isEmpty()) {
            payload.put("tags", tags);
        }

        return payload;
    }

    /**
     * Response from Notifer API.
     */
    public static class NotiferResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        private String id;
        private String topic;
        private String message;
        private int priority;
        private List<String> tags;

        public String getId() {
            return id;
        }

        public String getTopic() {
            return topic;
        }

        public String getMessage() {
            return message;
        }

        public int getPriority() {
            return priority;
        }

        public List<String> getTags() {
            return tags;
        }

        @Override
        public String toString() {
            return String.format("NotiferResponse{id='%s', topic='%s', priority=%d}", id, topic, priority);
        }
    }

    /**
     * Exception thrown when Notifer API request fails.
     */
    public static class NotiferException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        public NotiferException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public NotiferException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
