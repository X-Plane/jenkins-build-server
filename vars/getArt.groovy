def call() {
    if(isUnix()) {
        sshagent(['tylers-ssh']) {
            echo "Pulling SVN art assets too for later auto-testing"
            // Do recursive cleanup, just in case
            sh(returnStdout: true, script: "set +x find Aircraft  -type d -exec \"svn cleanup\" \\;")
            sh(returnStdout: true, script: "set +x find Resources -type d -exec \"svn cleanup\" \\;")
            sh(returnStdout: true, script: 'scripts/get_art.sh checkout tyler')
        }
    }
}

