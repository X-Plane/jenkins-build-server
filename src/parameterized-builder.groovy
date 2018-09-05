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
            'Windows' : { if(utils.build_windows) { node('windows') { timeout(60 * 2) { closure('Windows') } } } },
            'macOS'   : { if(utils.build_mac)     { node('mac')     { timeout(60 * 2) { closure('macOS')   } } } },
            'Linux'   : { if(utils.build_linux)   { node('linux')   { timeout(60 * 2) { closure('Linux')   } } } }
    )
}

def doCheckout(String platform) {
    // Nuke previous products
    boolean doClean = utils.toRealBool(clean_build)
    cleanCommand = doClean ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(utils.getExpectedXPlaneProducts(platform), cleanCommand, platform, utils)

    if(doClean) {
        for(String shaderDir : ['glsl120', 'glsl130', 'glsl150', 'spv', 'mlsl']) {
            String relPath = 'Resources/shaders/bin/' + shaderDir
            try {
                utils.chooseShellByPlatformNixWin("rm -Rf ${relPath}", "rd /s /q ${relPath}", platform)
            } catch(e) { }
        }
    }

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
                    message: "${heyYourBuild} of `${branch_name}` failed | <${logUrl}|Console Log (split by machine/task/subtask)> | <${BUILD_URL}|Build Info>")
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

            List prods = utils.getExpectedXPlaneProducts(platform)

            try {
                String shadersZip = "shaders_bin_${platform}.zip"
                zip(zipFile: shadersZip, archive: false, dir: 'Resources/shaders/bin/')
                prods.add(shadersZip)
            } catch(e) { }

            // Kit the installers for deployment
            if(utils.needsInstallerKitting(platform)) {
                String installer = utils.getExpectedXPlaneProducts(platform, true).last()
                String zip_target = utils.chooseByPlatformMacWinLin(['X-Plane11InstallerMac.zip', 'X-Plane11InstallerWindows.zip', 'X-Plane11InstallerLinux.zip'], platform)
                utils.chooseShellByPlatformMacWinLin([
                        "zip -r ${zip_target} \"X-Plane 11 Installer.app\"",
                        "zip -j ${zip_target} \"X-Plane 11 Installer.exe\"",
                        "cp \"${installer}\" \"X-Plane 11 Installer Linux\" && zip -j ${zip_target} \"X-Plane 11 Installer Linux\" && rm \"X-Plane 11 Installer Linux\"",
                ], platform)
                prods.push(zip_target)
            }
            archiveWithDropbox(prods, dropboxPath, true, utils)
        }
    } catch (e) {
        utils.sendEmail("Jenkins archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing build products.",
                e.toString())
        throw e
    }
}

def notifySuccess() {
    utils.replyToTrigger("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded.")
    String productsUrl = "${BUILD_URL}artifact/*zip*/archive.zip"
    String heyYourBuild = getSlackHeyYourBuild()
    slackSend(
            color: 'good',
            message: "${heyYourBuild} of ${branch_name} succeeded | <${productsUrl}|Download products> | <${BUILD_URL}|Build Info>")
}

String getSlackHeyYourBuild() {
    def userCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
    if(userCause != null) {
        String slackUserId = jenkinsToSlackUserId(userCause.getUserId())
        if(slackUserId.isEmpty()) {
            return 'Manual build'
        } else {
            return "Hey <@${slackUserId}>, your build"
        }
    }
    return 'Autotriggered build'
}

String jenkinsToSlackUserId(String jenkinsUserName) {
         if(jenkinsUserName == 'jennifer') { return 'UAFN64MEC' }
    else if(jenkinsUserName == 'tyler')    { return 'UAG6R8LHJ' }
    else if(jenkinsUserName == 'justsid')  { return 'UAFUMQESC' }
    else if(jenkinsUserName == 'chris')    { return 'UAG89NX9S' }
    else if(jenkinsUserName == 'philipp')  { return 'UAHMBUCV9' }
    else if(jenkinsUserName == 'ben')      { return 'UAHHSRPD5' }
    else if(jenkinsUserName == 'joerg')    { return 'UAHNGEP61' }
    else if(jenkinsUserName == 'austin')   { return 'UAGV8R9PS' }
    return ''
}
