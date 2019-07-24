def call(String checkoutDir) {
    dir(checkoutDir) {
        sshagent(['tylers-ssh']) {
            echo "Pulling SVN art assets too for later auto-testing"
            for(int i = 0; i < 5; ++i) { // retry a few times to account for connection issues
                try {
                    // Do recursive cleanup, just in case
                    sh(returnStdout: true, script: "set +x find Aircraft  -type d -exec \"svn cleanup\" \\;")
                    sh(returnStdout: true, script: "set +x find Resources -type d -exec \"svn cleanup\" \\;")
                    sh(returnStdout: true, script: 'scripts/get_art.sh --cleanup --clobber checkout tyler')
                    return
                } catch(e) { sleep(10) }
            }
        }
    }
}

