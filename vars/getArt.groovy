def call(String checkoutDir) {
    dir(checkoutDir) {
        // SVN is sloooooow to verify that nothing's changed---on Mac, it's like 8 minutes for a no-op checkout.
        // So, on successful checkout, we'll write a little file with the hash of the get art script, and
        // the next time we do a build, if the get_art.sh script hasn't changed *at all*, we'll skip checkout.
        String hashPath = 'scripts/get_art.sha1'
        String currentHash = sha1('scripts/get_art.sh')

        boolean needsUpdate = true
        try {
            String lastCheckedOutHashOfGetArt = readFile(hashPath).trim()
            needsUpdate = currentHash == lastCheckedOutHashOfGetArt
        } catch(e) { } // File not existing is fine, and normal-ish

        if(needsUpdate) {
            fileOperations([fileDeleteOperation(includes: hashPath)])

            sshagent(['tylers-ssh']) {
                echo "Pulling SVN art assets too for later auto-testing"
                for(int i = 0; i < 5; ++i) { // retry a few times to account for connection issues
                    try {
                        // Do recursive cleanup, just in case
                        sh(returnStdout: true, script: "set +x find Aircraft  -type d -exec \"svn cleanup\" \\;")
                        sh(returnStdout: true, script: "set +x find Resources -type d -exec \"svn cleanup\" \\;")
                        sh(returnStdout: true, script: 'scripts/get_art.sh --cleanup --clobber checkout tyler')
                        writeFile(file: hashPath, text: currentHash)
                        return
                    } catch(e) { sleep(10) }
                }
            }
        } else {
            echo "Art already up to date; skipping get_art.sh"
        }
    }
}

