// If this is an email-triggered build, the branch/tag/commit to build is in the email's subject line
branch_name = pmt_subject ? pmt_subject.trim() : branch_name

def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = pmt_subject
environment['pmt_from'] = pmt_from
environment['directory_suffix'] = directory_suffix
environment['build_windows'] = build_windows
environment['build_mac'] = build_mac
environment['build_linux'] = build_linux
environment['build_all_apps'] = build_all_apps
environment['build_type'] = build_type
utils.setEnvironment(environment, this.&notify)


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
try {
    stage('Respond')                       { utils.replyToTrigger("Build started.\n\nThe automated build of commit ${branch_name} is in progress.") }
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout) }
    stage('Build')                         { runOn3Platforms(this.&doBuild) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
    stage('Notify')                        { notifySuccess() }
} finally {
    if(utils.build_windows) {
        node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
    }
}

def runOn3Platforms(Closure c) {
    def closure = c
    parallel (
            'Windows' : { if(utils.build_windows) { node('windows') { closure('Windows') } } },
            'macOS'   : { if(utils.build_mac)     { node('mac')     { closure('macOS')   } } },
            'Linux'   : { if(utils.build_linux)   { node('linux')   { closure('Linux')   } } }
    )
}

def doCheckout(String platform) {
    // Nuke previous products
    cleanCommand = utils.toRealBool(clean_build) ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(utils.getExpectedXPlaneProducts(platform), cleanCommand, platform, utils)

    try {
        xplaneCheckout(branch_name, utils.getCheckoutDir(platform), platform)
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'X-Plane', branch_name, platform, e)
    }
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            def archiveDir = utils.getArchiveDirAndEnsureItExists(platform)
            def toBuild = utils.getExpectedXPlaneProducts(platform)
            echo 'Expecting to build: ' + toBuild.join(', ')
            if(!utils.toRealBool(force_build) && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                def config = getBuildToolConfiguration()

                // Generate our project files
                utils.chooseShellByPlatformMacWinLin(['./cmake.sh', 'cmd /C ""%VS140COMNTOOLS%vsvars32.bat" && cmake.bat"', "./cmake.sh ${config}"], platform)

                def projectFile = utils.chooseByPlatformNixWin("design_xcode/X-System.xcodeproj", "design_vstudio\\X-System.sln", platform)

                def pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''

                String target = utils.build_all_apps ? "ALL_BUILD" : "X-Plane"
                if(utils.toRealBool(clean_build)) {
                    utils.chooseShellByPlatformMacWinLin([
                            "set -o pipefail && xcodebuild -project ${projectFile} clean ${pipe_to_xcpretty} && xcodebuild -scheme \"${target}\" -config \"${config}\" -project ${projectFile} clean ${pipe_to_xcpretty} && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                            "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                            'cd design_linux && make clean'
                    ], platform)
                }

                utils.chooseShellByPlatformMacWinLin([
                        "set -o pipefail && xcodebuild -scheme \"${target}\" -config \"${config}\" -project ${projectFile} build ${pipe_to_xcpretty}",
                        "\"${tool 'MSBuild'}\" /t:Build /m /p:Configuration=\"${config}\" /p:Platform=\"x64\" /p:ProductVersion=11.${env.BUILD_NUMBER} design_vstudio\\" + (utils.build_all_apps ? "X-System.sln" : "source_code\\app\\X-Plane-f\\X-Plane.vcxproj"),
                        "cd design_linux && make -j\$(nproc) " + (utils.build_all_apps ? '' : "X-Plane")
                ], platform)
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'X-Plane', branch_name, utils.getCommitId(platform), platform, e)
            String heyYourBuild = getSlackHeyYourBuild()
            String logUrl = "${BUILD_URL}flowGraphTable/"
            slackSend(
                    color: 'danger',
                    message: "${heyYourBuild} of ${branch_name} failed | <${logUrl}|Console Log (split by machine/task/subtask)> | <${BUILD_URL}|Build Info>")
        }
    }
}

def getBuildToolConfiguration() {
    return utils.getBuildToolConfiguration()
}

def doArchive(String platform) {
    try {
        def checkoutDir = utils.getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = utils.getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "find . -name '*.app' -exec zip -r '{}'.zip '{}' \\;"
                sh "find . -name '*.dSYM' -exec zip -r '{}'.zip '{}' \\;"
            }

            archiveWithDropbox(utils.getExpectedXPlaneProducts(platform), dropboxPath, true, utils)
        }
    } catch (e) {
        utils.sendEmail("Jenkins archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing build products.",
                e.toString())
        throw e
    }
}

def notifySuccess(String platform) {
    utils.replyToTrigger("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded.")
    String productsUrl = "${BUILD_URL}artifact/*zip*/archive.zip"
    String heyYourBuild = getSlackHeyYourBuild()
    slackSend(
            color: 'good',
            message: "${heyYourBuild} of ${branch_name} succeeded | <${productsUrl}|Download all build products> | <${BUILD_URL}|Build Info>")
}

String getSlackHeyYourBuild() {
    def userCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
    if(userCause != null) {
        String slackUserName = jenkinsToSlackUserName(userCause.getUserName())
        if(slackUserName.isEmpty()) {
            return 'Manual build'
        } else {
            return "Hey @${slackUserName}, your build"
        }
    }
    return 'Autotriggered build'
}

String jenkinsToSlackUserName(String jenkinsUserName) {
    if(jenkinsUserName == 'jennifer') {
        return 'Jennifer'
    } else if(jenkinsUserName == 'tyler') {
        return 'Tyler Young'
    } else if(jenkinsUserName == 'justsid') {
        return 'justsid'
    } else if(jenkinsUserName == 'chris') {
        return 'Chris Serio'
    } else if(jenkinsUserName == 'philipp') {
        return 'Philipp'
    } else if(jenkinsUserName == 'ben') {
        return 'Ben Supnik'
    } else if(jenkinsUserName == 'joerg') {
        return 'Jörg'
    } else if(jenkinsUserName == 'austin') {
        return 'Austin Meyer'
    }
    return ''
}
