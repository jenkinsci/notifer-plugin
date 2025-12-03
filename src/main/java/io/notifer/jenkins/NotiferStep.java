package io.notifer.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.*;

/**
 * Pipeline step for sending notifications to Notifer.
 *
 * Usage in Jenkinsfile:
 * <pre>
 * notifer(
 *     credentialsId: 'my-topic-token',
 *     topic: 'ci-notifications',
 *     message: 'Build completed',
 *     title: 'Jenkins Build',
 *     priority: 3,
 *     tags: ['jenkins', 'build']
 * )
 * </pre>
 */
public class NotiferStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String credentialsId;
    private final String topic;
    private String message;
    private String title;
    private int priority = 0; // 0 = auto-detect based on result
    private String tags;
    private boolean failOnError = false;

    /**
     * Constructor with required parameters.
     */
    @DataBoundConstructor
    public NotiferStep(@NonNull String credentialsId, @NonNull String topic) {
        this.credentialsId = credentialsId;
        this.topic = topic;
    }

    // --- Getters ---

    @NonNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @NonNull
    public String getTopic() {
        return topic;
    }

    public String getMessage() {
        return message;
    }

    public String getTitle() {
        return title;
    }

    public int getPriority() {
        return priority;
    }

    public String getTags() {
        return tags;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    // --- Setters ---

    @DataBoundSetter
    public void setMessage(String message) {
        this.message = message;
    }

    @DataBoundSetter
    public void setTitle(String title) {
        this.title = title;
    }

    @DataBoundSetter
    public void setPriority(int priority) {
        this.priority = Math.max(0, Math.min(5, priority));
    }

    /**
     * Setter that accepts both String and List from Pipeline scripts.
     * Converts list to comma-separated string.
     */
    @DataBoundSetter
    public void setTags(Object tags) {
        if (tags == null) {
            this.tags = null;
        } else if (tags instanceof List) {
            List<?> tagList = (List<?>) tags;
            if (!tagList.isEmpty()) {
                this.tags = tagList.stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            } else {
                this.tags = null;
            }
        } else {
            this.tags = tags.toString();
        }
    }

    @DataBoundSetter
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new NotiferStepExecution(this, context);
    }

    /**
     * Step execution implementation.
     */
    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
            justification = "Step execution is synchronous and completes immediately, no serialization needed")
    private static class NotiferStepExecution extends SynchronousNonBlockingStepExecution<NotiferClient.NotiferResponse> {
        private static final long serialVersionUID = 1L;

        private final transient NotiferStep step;

        NotiferStepExecution(NotiferStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected NotiferClient.NotiferResponse run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Run<?, ?> run = getContext().get(Run.class);
            EnvVars envVars = getContext().get(EnvVars.class);
            PrintStream logger = listener.getLogger();
            Result result = run.getResult();

            // Get token from credentials
            String token = getTokenFromCredentials(step.credentialsId, run.getParent());
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("Could not retrieve token from credentials: " + step.credentialsId);
            }

            // Expand environment variables
            String topic = envVars.expand(step.topic);

            // Build default message if not provided
            String message = step.message;
            if (message == null || message.isEmpty()) {
                message = buildDefaultMessage(run, result);
            } else {
                message = envVars.expand(message);
            }

            // Build default title if not provided
            String title = step.title;
            if (title == null || title.isEmpty()) {
                title = buildDefaultTitle(run, result);
            } else {
                title = envVars.expand(title);
            }

            // Determine priority based on result if using auto (0)
            int priority = step.priority > 0 ? step.priority : getPriorityForResult(result);

            // Parse tags from comma-separated string
            List<String> tags = parseTags(step.tags, result, envVars);

            logger.println("[Notifer] Sending notification to topic: " + topic);

            try {
                NotiferClient client = new NotiferClient(token);
                NotiferClient.NotiferResponse response = client.send(topic, message, title, priority, tags);

                logger.println("[Notifer] Notification sent successfully. ID: " + response.getId());
                return response;

            } catch (NotiferClient.NotiferException e) {
                String errorMessage = "[Notifer] Failed to send notification: " + e.getMessage();

                if (step.failOnError) {
                    throw new RuntimeException(errorMessage, e);
                } else {
                    logger.println(errorMessage);
                    return null;
                }
            }
        }

        private String getTokenFromCredentials(String credentialsId, Item item) {
            StringCredentials credentials = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentials(
                            StringCredentials.class,
                            item,
                            item instanceof hudson.model.Queue.Task
                                    ? ((hudson.model.Queue.Task) item).getDefaultAuthentication()
                                    : ACL.SYSTEM,
                            Collections.emptyList()
                    ),
                    CredentialsMatchers.withId(credentialsId)
            );

            return credentials != null ? credentials.getSecret().getPlainText() : null;
        }

        @SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE",
                justification = "Intentional \\n for notification message format, not console output")
        private String buildDefaultMessage(Run<?, ?> run, Result result) {
            String jobName = run.getParent().getFullDisplayName();
            int buildNumber = run.getNumber();
            String status = result != null ? result.toString() : "RUNNING";
            String url = Jenkins.get().getRootUrl() + run.getUrl();

            return String.format("Build #%d %s\nJob: %s\nDetails: %s",
                    buildNumber, status, jobName, url);
        }

        private String buildDefaultTitle(Run<?, ?> run, Result result) {
            String jobName = run.getParent().getDisplayName();
            String status = result != null ? result.toString() : "RUNNING";
            return String.format("[%s] %s #%d", status, jobName, run.getNumber());
        }

        private int getPriorityForResult(Result result) {
            if (result == null || result == Result.SUCCESS) {
                return 2; // Low priority for success
            }
            if (result == Result.UNSTABLE) {
                return 3; // Medium for unstable
            }
            if (result == Result.FAILURE) {
                return 5; // High for failure
            }
            return 3;
        }

        private List<String> parseTags(String tagsString, Result result, EnvVars envVars) {
            List<String> tagList = new ArrayList<>();

            // Add result as tag
            if (result != null) {
                tagList.add(result.toString().toLowerCase());
            }

            // Add jenkins tag
            tagList.add("jenkins");

            // Parse custom tags
            if (tagsString != null && !tagsString.isEmpty()) {
                String expanded = envVars.expand(tagsString);
                String[] parts = expanded.split("[,\\s]+");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && tagList.size() < 5) {
                        tagList.add(trimmed);
                    }
                }
            }

            // Limit to 5 tags
            if (tagList.size() > 5) {
                tagList = tagList.subList(0, 5);
            }

            return tagList;
        }
    }

    /**
     * Descriptor for the step.
     */
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(
                    Run.class,
                    TaskListener.class,
                    EnvVars.class
            ));
        }

        @Override
        public String getFunctionName() {
            return "notifer";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Send Notifer Notification";
        }

        // --- Form Validation ---
        // Note: credentialsId and topic use class="required" in jelly

        @POST
        public FormValidation doCheckPriority(@QueryParameter int value) {
            if (value < 0 || value > 5) {
                return FormValidation.warning("Priority should be 0 (auto) or between 1 and 5");
            }
            return FormValidation.ok();
        }

        /**
         * Fill priority dropdown.
         */
        @POST
        public ListBoxModel doFillPriorityItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Auto (based on result)", "0");
            items.add("1 - Minimum", "1");
            items.add("2 - Low", "2");
            items.add("3 - Default", "3");
            items.add("4 - High", "4");
            items.add("5 - Maximum", "5");
            return items;
        }

        /**
         * Fill credentials dropdown.
         */
        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item,
                @QueryParameter String credentialsId) {

            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ)
                        && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }

            return result
                    .includeEmptyValue()
                    .includeMatchingAs(
                            item instanceof hudson.model.Queue.Task
                                    ? ((hudson.model.Queue.Task) item).getDefaultAuthentication()
                                    : ACL.SYSTEM,
                            item,
                            StringCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always()
                    )
                    .includeCurrentValue(credentialsId);
        }
    }
}
