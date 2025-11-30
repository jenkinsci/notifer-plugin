# Notifer Jenkins Plugin

Send notifications to [Notifer](https://notifer.io) topics from Jenkins pipelines and freestyle jobs.

## Features

- **Pipeline Step**: Use `notifer()` in declarative and scripted pipelines
- **Post-Build Action**: Add notifications to freestyle jobs
- **Environment Variables**: Full support for variable expansion
- **Conditional Notifications**: Send based on build result (success, failure, unstable, aborted)
- **Auto Priority**: Automatically set priority based on build result
- **Credentials Integration**: Secure token storage using Jenkins Credentials

## Installation

### From Jenkins Update Center (Recommended)

1. Go to **Manage Jenkins** > **Plugins** > **Available plugins**
2. Search for "Notifer"
3. Install and restart Jenkins

### Manual Installation

1. Download the latest `.hpi` file from [Releases](https://github.com/jenkinsci/notifer-plugin/releases)
2. Go to **Manage Jenkins** > **Plugins** > **Advanced settings**
3. Upload the `.hpi` file
4. Restart Jenkins

## Setup

### 1. Create a Topic Access Token in Notifer

1. Go to your topic in [Notifer](https://app.notifer.io)
2. Open **Access Tokens**
3. Create a token with **Write** or **Read/Write** access
4. Copy the token (starts with `tk_`)

### 2. Add Token to Jenkins Credentials

1. Go to **Manage Jenkins** > **Credentials**
2. Select appropriate scope (global or folder)
3. Click **Add Credentials**
4. Kind: **Secret text**
5. Secret: Paste your Notifer token
6. ID: Give it a name like `notifer-my-topic`
7. Click **Create**

## Usage

### Pipeline (Declarative)

```groovy
pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                sh 'make build'
            }
        }
    }

    post {
        // Use in 'always' block for auto-generated message and priority based on result
        always {
            notifer(
                credentialsId: 'notifer-my-topic',
                topic: 'ci-builds'
                // message and priority are auto-generated based on build result
            )
        }
        // Or use specific blocks with custom messages (use single quotes for env vars)
        success {
            notifer(
                credentialsId: 'notifer-my-topic',
                topic: 'ci-builds',
                message: 'Build #${BUILD_NUMBER} succeeded',
                priority: 2
            )
        }
        failure {
            notifer(
                credentialsId: 'notifer-my-topic',
                topic: 'ci-builds',
                message: 'Build #${BUILD_NUMBER} FAILED!',
                priority: 5,
                tags: 'failure, urgent'
            )
        }
    }
}
```

### Pipeline (Scripted)

```groovy
node {
    try {
        stage('Build') {
            sh 'make build'
        }

        notifer(
            credentialsId: 'notifer-my-topic',
            topic: 'ci-builds',
            message: 'Build successful: ${JOB_NAME} #${BUILD_NUMBER}'
        )
    } catch (Exception e) {
        notifer(
            credentialsId: 'notifer-my-topic',
            topic: 'ci-builds',
            message: 'Build failed: ${JOB_NAME}',
            priority: 5
        )
        throw e
    }
}
```

### Freestyle Job

1. Open your job configuration
2. Add **Post-build Action** > **Send Notifer Notification**
3. Select credentials and enter topic name
4. Optionally configure message, priority, and when to notify

## Step Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `credentialsId` | String | **Yes** | - | Jenkins credentials ID containing the topic token |
| `topic` | String | **Yes** | - | Topic name to send notification to |
| `message` | String | No | Auto | Notification message (use `${VAR}` for env vars). Auto-generated if empty. |
| `title` | String | No | Auto | Optional message title. Auto-generated if empty. |
| `priority` | int | No | 0 (auto) | 0=auto (based on result), 1-5=manual |
| `tags` | String | No | - | Comma-separated tags (max 5) |
| `failOnError` | boolean | No | false | Fail build if notification fails |

## Post-Build Action Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `credentialsId` | String | **Yes** | - | Jenkins credentials ID |
| `topic` | String | **Yes** | - | Topic name |
| `message` | String | No | Auto | Custom message (auto-generated if empty) |
| `title` | String | No | Auto | Custom title (auto-generated if empty) |
| `priority` | int | No | Auto | 0=auto, 1-5=manual |
| `tags` | String | No | - | Comma-separated tags |
| `notifySuccess` | boolean | No | true | Notify on success |
| `notifyFailure` | boolean | No | true | Notify on failure |
| `notifyUnstable` | boolean | No | true | Notify on unstable |
| `notifyAborted` | boolean | No | false | Notify on aborted |

## Environment Variables

The following variables are available in messages:

- `${BUILD_NUMBER}` - Build number
- `${JOB_NAME}` - Job name
- `${BUILD_URL}` - Full URL to build
- `${GIT_COMMIT}` - Git commit hash (if using Git)
- `${GIT_BRANCH}` - Git branch name (if using Git)

## Examples

### Different Topics per Environment

```groovy
// Use single quotes for env variable substitution by the plugin
notifer(
    credentialsId: 'notifer-ci',
    topic: 'builds-${BRANCH_NAME}',
    message: 'Deployed to ${BRANCH_NAME}'
)
```

### With Test Results

```groovy
script {
    def testResults = junit 'target/test-results/*.xml'
    // Use double quotes here since we need Groovy to evaluate testResults
    notifer(
        credentialsId: 'notifer-ci',
        topic: 'ci-builds',
        message: "Build Report:\n- Tests: ${testResults.totalCount}\n- Passed: ${testResults.passCount}\n- Failed: ${testResults.failCount}",
        priority: testResults.failCount > 0 ? 4 : 2
    )
}
```

## Troubleshooting

### "Could not retrieve token from credentials"

1. Verify the credentials ID is correct
2. Check the credential is of type "Secret text"
3. Ensure the job has access to the credentials scope

### "Failed to send notification"

1. Verify the token has **write** access
2. Check the topic exists and is private
3. Test the token with curl:
   ```bash
   curl -X POST https://app.notifer.io/YOUR_TOPIC \
     -H "X-Topic-Token: tk_your_token" \
     -H "Content-Type: application/json" \
     -d '{"message": "test"}'
   ```

## Development

### Build

```bash
mvn clean package
```

### Run locally

```bash
mvn hpi:run
```

The `.hpi` file will be in `target/notifer.hpi`.

## License

MIT License - see [LICENSE](LICENSE)

## Links

- [Notifer](https://notifer.io)
- [Notifer Documentation](https://docs.notifer.io)
- [Issue Tracker](https://github.com/jenkinsci/notifer-plugin/issues)
