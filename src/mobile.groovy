def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['directory_suffix'] = directory_suffix
environment['build_windows'] = 'false'
environment['build_mac'] = 'false'
environment['build_linux'] = 'false'
environment['build_all_apps'] = 'true'
environment['build_type'] = 'release'
environment['override_checkout_dir'] = 'iphone-' + directory_suffix
utils.setEnvironment(environment, this.&notify, this.steps)

alerted_via_slack = false
doClean = utils.toRealBool(clean_build)
forceBuild = utils.toRealBool(force_build)

//--------------------------------------------------------------------------------------------------------------------------------
// RUN THE BUILD
// This is where the magic happens.
// X-Plane builds take place across a number of stages, each running on 3 platforms (Mac, Windows, & Linux).
//
// If any stage fails, we send an email to the appropriate person.
// "The appropriate person" depends on
//     a) which stage failed, and
//     b) whether this was an email-triggered build (requested by an individual dev) or a build triggered by monitoring a particular branch in Git.
//
// For failures in the build stage, the person to email is the person who requested the build (for email-triggered builds) or
// the "responsible parties" (everyone who made a commit since our last successful build.
//
// For failures in any other stage, the person to email is Tyler, the build farm maintainer.
//--------------------------------------------------------------------------------------------------------------------------------
platform = 'macOS' // This is the platform of the *builder*
node('ios') {
    try {
        stage('Checkout') { timeout(60 * 2) { doCheckout(platform) } }
        stage('Build')    { timeout(60 * 2) { doBuild(platform)    } }
        stage('Archive')  { timeout(60 * 2) { doArchive(platform)  } }
        stage('Notify') {
            notifySuccess()
            jiraSendBuildInfo(branch: branch_name, site: 'x-plane.atlassian.net')
        }
    } finally {
        String parseRulesUrl = 'https://raw.githubusercontent.com/X-Plane/jenkins-build-server/master/log-parser-builds.txt'
        utils.chooseShellByPlatformNixWin("curl ${parseRulesUrl} -O", "C:\\msys64\\usr\\bin\\curl.exe ${parseRulesUrl} -O", platform)
        step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: "${pwd()}/log-parser-builds.txt", useProjectRule: false])
    }
}
def doCheckout(String platform) {
    String checkoutDir = utils.getCheckoutDir(platform)
    dir(checkoutDir) {
        utils.nukeIfExist(['X-Plane.xcarchive.zip'], platform)
        try {
            xplaneCheckout(branch_name, checkoutDir, platform)
        } catch(e) {
            notifyBrokenCheckout(utils.&sendEmail, 'X-Plane Mobile', branch_name, platform, e)
            if(!alerted_via_slack) {
                alerted_via_slack = slackBuildInitiatorFailure("failed to check out Mobile's `${branch_name}` | <${BUILD_URL}flowGraphTable/|Log (split by machine & task)>")
            }
        }
    }
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            toBuild = ['X-Plane.xcarchive']
            if(!forceBuild && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                if(doClean) {
                    sh "rm -Rf ~/Library/Developer/Xcode/DerivedData/*"
                }
                String cleanCommand = doClean ? 'clean' : ''
                String buildCommand = "xcodebuild -scheme \"SIM-V10 Release\" -project iphone.xcodeproj ${cleanCommand} archive -sdk iphoneos -archivePath X-Plane.xcarchive"
                String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
                sh "set -o pipefail && ${buildCommand} ${pipe_to_xcpretty}"
            }
        } catch (e) {
            String user = atSlackUser()
            if(user) {
                user += ' '
            }
            slackSend(
                    color: 'danger',
                    message: "${user}Build of Mobile's `${branch_name}` failed on ${platform} | <${BUILD_URL}parsed_console/|Parsed Console Log> | <${BUILD_URL}flowGraphTable/|Log (split by machine & task)>")
            alerted_via_slack = true
            notifyDeadBuild(utils.&sendEmail, 'X-Plane Mobile', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

String getArchiveDirAndEnsureItExists(String platform) {
    String path = utils.getArchiveDir(platform, 'mobile')
    fileOperations([folderCreateOperation(path)])
    return path
}

def doArchive(String platform) {
    try {
        def checkoutDir = utils.getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "find . -name '*.xcarchive' -exec zip -rq --symlinks '{}'.zip '{}' \\;"
            }
            archiveWithDropbox(["X-Plane.xcarchive.zip"], dropboxPath, true, utils)
        }
    } catch (e) {
        utils.sendEmail("Jenkins archive step failed on ${platform} [Mobile's ${branch_name}]",
                "Archive step failed on ${platform}, Mobile branch ${branch_name}. This is probably due to missing build products.",
                e.toString())
        throw e
    }
}

def notifySuccess() {
    if(!alerted_via_slack) {
        String productsUrl = "${BUILD_URL}artifact/*zip*/archive.zip"
        alerted_via_slack = slackBuildInitiatorSuccess("finished building `${branch_name}` | <${productsUrl}|Download products> | <${BUILD_URL}|Build Info>")
    }
}

