def call(String branchName='', String checkoutDir='', String platform='') {
    dir(checkoutDir) {
        try {
            sshagent(['tylers-ssh']) {
                echo "Checking out ${branchName} on ${platform}"
                sh "git branch"
                sh "git fetch"
                sh "git checkout ${b}"
                try {
                    sh "git pull"
                } catch(e) { } // If we're in detached HEAD mode, pull will fail

                String commitId = ""
                if(isUnix()) {
                    commitId = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                } else {
                    commitId = bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")[1]
                }
                echo "Checked out commit ${commitId}"

                if(isUnix()) {
                    echo "Pulling SVN art assets too for later auto-testing"
                    // Do recursive cleanup, just in case
                    sh(returnStdout: true, script: "set +x find Aircraft  -type d -exec \"svn cleanup\" \\;")
                    sh(returnStdout: true, script: "set +x find Resources -type d -exec \"svn cleanup\" \\;")
                    sh(returnStdout: true, script: 'scripts/get_art.sh checkout tyler')
                }
            }
        } catch(e) {
            currentBuild.result = "FAILED"
            notifyBuild("Jenkins Git checkout is broken on ${platform} [${branchName}]",
                    "${platform} Git checkout failed on branch ${branchName}. We will be unable to continue until this is fixed.",
                    e.toString(),
                    'tyler@x-plane.com')
            throw e
        }
    }
}

