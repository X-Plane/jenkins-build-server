def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['directory_suffix'] = directory_suffix
environment['build_ios'] = true
environment['build_type'] = 'release'
environment['override_checkout_dir'] = 'iphone-' + directory_suffix
utils.setEnvironment(environment, this.&notify, this.steps)

alerted_via_slack = false
doClean = utils.toRealBool(clean_build)
forceBuild = utils.toRealBool(force_build)
buildIOS = build.contains('iOS')
buildAndroid = build.contains('Android')

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
try {
    node('ios') {
        stage('Checkout') { timeout(60 * 2) { doCheckout(platform) } }
        stage('Build')    { timeout(60 * 2) { doBuild(platform)    } }
        stage('Archive')  { timeout(60 * 2) { doArchive(platform)  } }
        stage('Notify') {
            notifySuccess()
            jiraSendBuildInfo(branch: branch_name, site: 'x-plane.atlassian.net')
        }
    }
} finally {
    node('master') {
        String parseRulesUrl = 'https://raw.githubusercontent.com/X-Plane/jenkins-build-server/master/log-parser-builds.txt'
        utils.chooseShellByPlatformNixWin("curl ${parseRulesUrl} -O", "C:\\msys64\\usr\\bin\\curl.exe ${parseRulesUrl} -O")
        step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: "${pwd()}/log-parser-builds.txt", useProjectRule: false])
    }
}
def doCheckout(String platform) {
    String checkoutDir = utils.getCheckoutDir(platform)
    dir(checkoutDir) {
        utils.nukeIfExist(buildProducts(platform), platform)
        try {
            xplaneCheckout(branch_name, checkoutDir, platform, "ssh://tyler@dev.x-plane.com/admin/git-xplane/iphone.git")
        } catch(e) {
            notifyBrokenCheckout(utils.&sendEmail, 'X-Plane Mobile', branch_name, platform, e)
            if(!alerted_via_slack) {
                alerted_via_slack = slackBuildInitiatorFailure("failed to check out Mobile's `${branch_name}` | <${BUILD_URL}flowGraphTable/|Log (split by machine & task)>")
            }
        }
    }
}

List<String> buildProducts(String platform) {
    List<String> toBuild = []
    if(buildIOS) { toBuild.push('X-Plane.xcarchive.zip') }
    if(buildAndroid) { toBuild.push('app-debug.apk') }
    return toBuild
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            List<String> toBuild = buildProducts(platform)
            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            if(!forceBuild && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This Mobile commit was already built in ${archiveDir}"
            } else { // Actually build some stuff!
                if(buildIOS) {
                    if(doClean) {
                        sh "rm -Rf ~/Library/Developer/Xcode/DerivedData/*"
                    }
                    String cleanCommand = doClean ? 'clean' : ''
                    String buildCommand = "xcodebuild -scheme \"X-Plane NODEV_OPT\" -project iphone.xcodeproj ${cleanCommand} archive -sdk iphoneos -archivePath X-Plane.xcarchive"
                    String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
                    sh "set -o pipefail && ${buildCommand} ${pipe_to_xcpretty}"
                }
                if(buildAndroid) {
                    dir('android/XPlane10/') {
                        if(doClean) {
                            sh "./gradlew clean"
                        }
                        sh "./gradlew assembleDebug"
                        sh "mv -f app/build/outputs/apk/debug/*.apk ../../"
                        // TODO: gradlew installDebug or test or testDebugUnitTest on the emulator and run it: https://developer.android.com/studio/build/building-cmdline#RunningOnEmulator
                    }
                }
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

            if(buildIOS) { // The "archive" is actually a directory.. we need to ZIP it, then operate on the ZIP file
                sh "find . -name '*.xcarchive' -exec zip -rq --symlinks '{}'.zip '{}' \\;"
            }

            // Tyler says: when we could theoretically _do_ something with the products, we can go back to storing in Dropbox
            //archiveWithDropbox(buildProducts(platform), dropboxPath, true, utils)
            archiveArtifacts artifacts: buildProducts(platform).join(', '), fingerprint: true, onlyIfSuccessful: false
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
        alerted_via_slack = slackBuildInitiatorSuccess("finished building Mobile's `${branch_name}` | <${productsUrl}|Download products> | <${BUILD_URL}|Build Info>")
    }
}

