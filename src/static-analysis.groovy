def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
//environment['pmt_subject'] = pmt_subject
//environment['pmt_from'] = pmt_from
environment['directory_suffix'] = 'static-analysis'
environment['release_build'] = 'false'
environment['steam_build'] = 'false'
environment['build_windows'] = 'false'
environment['build_mac'] = 'true'
environment['build_linux'] = 'false'
environment['build_all_apps'] = 'false'
environment['dev_build'] = 'false'
utils.setEnvironment(environment, this.&notify)

try {
    stage('Respond')                       { utils.replyToTrigger('Analysis started.\n\nThe static analysis of commit ' + env.subject + ' is in progress.') }
    stage('Checkout')                      { runOnMac(this.&doCheckout) }
    stage('Analyze')                       { runOnMac(this.&doAnalysis) }
    stage('Archive')                       { runOnMac(this.&doArchive) }
    stage('Notify')                        { utils.replyToTrigger('SUCCESS!\n\nThe static analysis of commit ' + env.subject + ' succeeded.') }
} catch(e) {
    utils.replyToTrigger('The static analysis of commit ' + env.subject + ' failed.', e.toString())
    throw e
}


def runOnMac(Closure c) {
    def closure = c
    node('mac') { closure('macOS') }
}

def doCheckout(String platform) {
    try {
        xplaneCheckout(branch_name, utils.getCheckoutDir(platform), platform)
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'X-Plane', branch_name, platform, e)
    }
}

def doAnalysis(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        def pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
        sh './cmake.sh'
        // Infuriatingly, Xcode returns an error if there's nothing to clean
        try {
            sh "xcodebuild -scheme \"X-Plane Debug\" -project design_xcode/X-System.xcodeproj clean ${pipe_to_xcpretty}"
        } catch(e) { }
        // xcodebuild returns 1 in the event of any issues found... obviously that still means the *analysis* went correctly
        sh(returnStatus: true, script: "xcodebuild -scheme \"X-Plane Debug\" -project design_xcode/X-System.xcodeproj analyze ${pipe_to_xcpretty} > analysis.txt")
    }
}

def doArchive(String platform) {
    try {
        def checkoutDir = utils.getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = utils.getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"
            archiveWithDropbox(['analysis.txt'], dropboxPath, true, utils)
        }
    } catch (e) {
        utils.sendEmail("Jenkins archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing build products.",
                e.toString())
        throw e
    }
}
