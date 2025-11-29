package io.notifer.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
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

import edu.umd.cs.findbugs.annotations.NonNull;
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
    private final String message;
    private String title;
    private int priority = 3;
    private List<String> tags;
    private boolean failOnError = false;

    /**
     * Constructor with required parameters.
     */
    @DataBoundConstructor
    public NotiferStep(@NonNull String credentialsId, @NonNull String topic, @NonNull String message) {
        this.credentialsId = credentialsId;
        this.topic = topic;
        this.message = message;
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

    @NonNull
    public String getMessage() {
        return message;
    }

    public String getTitle() {
        return title;
    }

    public int getPriority() {
        return priority;
    }

    public List<String> getTags() {
        return tags;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    // --- Setters ---

    @DataBoundSetter
    public void setTitle(String title) {
        this.title = title;
    }

    @DataBoundSetter
    public void setPriority(int priority) {
        this.priority = Math.max(1, Math.min(5, priority));
    }

    @DataBoundSetter
    public void setTags(List<String> tags) {
        this.tags = tags;
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

            // Get token from credentials
            String token = getTokenFromCredentials(step.credentialsId, run.getParent());
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("Could not retrieve token from credentials: " + step.credentialsId);
            }

            // Expand environment variables
            String topic = envVars.expand(step.topic);
            String message = envVars.expand(step.message);
            String title = step.title != null ? envVars.expand(step.title) : null;

            // Expand tags
            List<String> tags = step.tags;
            if (tags != null) {
                List<String> expandedTags = new ArrayList<>();
                for (String tag : tags) {
                    expandedTags.add(envVars.expand(tag));
                }
                tags = expandedTags;
            }

            logger.println("[Notifer] Sending notification to topic: " + topic);

            try {
                NotiferClient client = new NotiferClient(token);
                NotiferClient.NotiferResponse response = client.send(topic, message, title, step.priority, tags);

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

        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Credentials are required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTopic(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Topic is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckMessage(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Message is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckPriority(@QueryParameter int value) {
            if (value < 1 || value > 5) {
                return FormValidation.warning("Priority should be between 1 and 5");
            }
            return FormValidation.ok();
        }

        /**
         * Fill credentials dropdown.
         */
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
