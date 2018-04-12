def call(String checkoutDir) {
    if(isUnix()) {
        dir(checkoutDir) {
            sshagent(['tylers-ssh']) {
                echo "Pulling SVN art assets too for later auto-testing"
                // Do recursive cleanup, just in case
                sh(returnStdout: true, script: "set +x find Aircraft  -type d -exec \"svn cleanup\" \\;")
                sh(returnStdout: true, script: "set +x find Resources -type d -exec \"svn cleanup\" \\;")
                sh(returnStdout: true, script: 'scripts/get_art.sh --clobber checkout tyler')
            }
        }
    }
}

