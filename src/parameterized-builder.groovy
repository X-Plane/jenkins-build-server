// If this is an email-triggered build, the branch/tag/commit to build is in the email's subject line
//branch_name = pmt_subject ? pmt_subject.trim() : branch_name

def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
//environment['pmt_subject'] = pmt_subject
//environment['pmt_from'] = pmt_from
environment['directory_suffix'] = directory_suffix
environment['build_windows'] = build_windows
environment['build_mac'] = build_mac
environment['build_linux'] = build_linux
environment['build_all_apps'] = 'true'
environment['build_type'] = build_type
environment['products_to_build'] = products_to_build
utils.setEnvironment(environment, this.&notify)

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
try {
    stage('Respond')                       { utils.replyToTrigger("Build started.\n\nThe automated build of commit ${branch_name} is in progress.") }
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout, true) }
    stage('Build') {
        if(utils.build_windows) { // shaders will get built on Windows as part of the normal build process
            runOn3Platforms(this.&doBuild)
        } else { // gotta handle shaders specially; we can do this on Windows in parallel with the other platforms (win!)
            parallel (
                    'Windows' : {                           node('windows') { buildAndArchiveShaders() } },
                    'macOS'   : { if(utils.build_mac)     { node('mac')     { timeout(60 * 2) { doBuild('macOS')   } } } },
                    'Linux'   : { if(utils.build_linux)   { node('linux')   { timeout(60 * 2) { doBuild('Linux')   } } } }
            )
        }
    }
    stage('Unit Test')                     { runOn3Platforms(this.&doUnitTest) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
    stage('Notify')                        { if(!alerted_via_slack) { notifySuccess() } }
} finally {
    if(utils.build_windows) {
        node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
    }
}

def runOn3Platforms(Closure c, boolean force_windows=false) {
    def closure = c
    parallel (
            'Windows' : { if(utils.build_windows || force_windows) { node('windows') { timeout(60 * 2) { closure('Windows') } } } },
            'macOS'   : { if(utils.build_mac)                      { node('mac')     { timeout(60 * 2) { closure('macOS')   } } } },
            'Linux'   : { if(utils.build_linux)                    { node('linux')   { timeout(60 * 2) { closure('Linux')   } } } }
    )
}

boolean supportsCatch2Tests(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            return fileExists('source_code/test/catch2_tests/CMakeLists.txt')
        } catch(e) { }
        return false
    }
}

String testXmlTarget(platform) {
    return "test_report_${platform}.xml"
}

String getCatch2Executable(String platform) {
    String appExt = utils.chooseByPlatformMacWinLin([".app", ".exe", '-x86_64'], platform)
    return utils.addSuffix(["catch2_tests"], utils.app_suffix + appExt)[0]
}
def getAvailableApps(String platform) {
    def availableApps = [SIM: 'X-Plane', AFL: 'Airfoil Maker', PLN: 'Plane Maker', INS: 'X-Plane 11 Installer']
    if(supportsCatch2Tests(platform)) {
        availableApps['TEST'] = 'catch2_tests'
    }
    return availableApps
}
List<String> getProducts(String platform, boolean ignoreSymbols=false) {
    String appExtNormal = utils.chooseByPlatformMacWinLin([".app.zip", ".exe", '-x86_64'], platform)
    List<String> filesWithExt = []
    List<String> appNamesForWinSymbols = []
    getAvailableApps(platform).each { appAndName ->
        if(products_to_build.contains(appAndName.key)) {
            String nameWithSuffix = appAndName.value + utils.app_suffix
            // On Linux, the installer drops the app extension (sigh)
            filesWithExt.push(nameWithSuffix + (utils.isLinux(platform) && appAndName.key == 'INS' ? '' : appExtNormal))
            // and on Windows, only the installer's symbols include the app suffix...
            appNamesForWinSymbols.push(appAndName.key == 'INS' ? nameWithSuffix : appAndName.value)
        }
    }

    boolean needsSymbols = !ignoreSymbols && build_type.contains('NODEV_OPT_Prod')
    if(needsSymbols) {
        def symbolsSuffix = utils.chooseByPlatformMacWinLin(['.app.dSYM.zip', '_win.sym', '_lin.sym'], platform)
        String macAppsWithSymbols = products_to_build.contains('SIM') ? ['X-Plane'] : []
        def platformSymbols = utils.addSuffix(utils.chooseByPlatformMacWinLin([macAppsWithSymbols, appNamesForWinSymbols, filesWithExt], platform), symbolsSuffix)
        if(utils.isWindows(platform)) {
            platformSymbols += utils.addSuffix(appNamesForWinSymbols, ".pdb")
        }
        filesWithExt += platformSymbols
    }
    return filesWithExt
}

def doCheckout(String platform) {
    // Nuke previous products
    cleanCommand = doClean ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(getProducts(platform) + [testXmlTarget(platform)], cleanCommand, platform, utils)

    dir(utils.getCheckoutDir(platform)) {
        if(doClean) {
            for (String shaderDir : ['glsl120', 'glsl130', 'glsl150', 'spv', 'mlsl']) {
                String relPath = utils.isWindows(platform) ? 'Resources\\shaders\\bin\\' + shaderDir : 'Resources/shaders/bin/' + shaderDir
                try {
                    utils.chooseShellByPlatformNixWin("rm -Rf ${relPath}", "rd /s /q ${relPath}", platform)
                } catch (e) { }
            }
        }
        utils.nukeIfExist(['shaders_bin.zip'], platform)
    }

    try {
        xplaneCheckout(branch_name, utils.getCheckoutDir(platform), platform)
        if(products_to_build.contains('TEST')) {
            getArt(checkoutDir)
        }
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'X-Plane', branch_name, platform, e)
    }
}

List<String> getBuildTargets(String platform) {
    List<String> out = []
    def nixTargets = [SIM: 'X-Plane', AFL: 'Airfoil-Maker', PLN: 'Plane-Maker', INS: 'X-Plane-Installer', TEST: 'catch2_tests']
    String winPrefix = "design_vstudio\\source_code"
    def windowsTargets = [SIM: "${winPrefix}\\app\\X-Plane-f\\X-Plane.vcxproj", AFL: "${winPrefix}\\app\\Airfoil-Maker-f\\Airfoil-Maker.vcxproj", PLN: "${winPrefix}\\app\\Plane-Maker-f\\Plane-Maker.vcxproj", INS: "${winPrefix}\\app\\Installer-f\\X-Plane-Installer.vcxproj", TEST: "${winPrefix}\\test\\catch2_tests\\catch2_tests.vcxproj"]
    def platformTargets = utils.chooseByPlatformNixWin(nixTargets, windowsTargets, platform)
    getAvailableApps(platform).each { appAndName ->
        if(products_to_build.contains(appAndName.key)) {
            out.push(platformTargets[appAndName.key])
        }
    }
    return out
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            def archiveDir = utils.getArchiveDirAndEnsureItExists(platform)
            def toBuild = getProducts(platform)
            echo 'Expecting to build: ' + toBuild.join(', ')
            if(!forceBuild && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                String config = utils.getBuildToolConfiguration()

                // Generate our project files
                utils.chooseShellByPlatformMacWinLin(['./cmake.sh --no_gfxcc', 'cmd /C ""%VS140COMNTOOLS%vsvars32.bat" && cmake.bat --no_gfxcc"', "./cmake.sh ${config} --no_gfxcc"], platform)

                String projectFile = utils.chooseByPlatformNixWin("design_xcode/X-System.xcodeproj", "design_vstudio\\X-System.sln", platform)

                String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
                String msBuild = utils.isWindows(platform) ? "${tool 'MSBuild'}" : ''

                if(doClean) {
                    utils.chooseShellByPlatformMacWinLin([
                            "set -o pipefail && xcodebuild -project ${projectFile} clean ${pipe_to_xcpretty} && xcodebuild -scheme \"${target}\" -config \"${config}\" -project ${projectFile} clean ${pipe_to_xcpretty} && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                            "\"${msBuild}\" ${projectFile} /t:Clean",
                            'cd design_linux && make clean'
                    ], platform)
                }

                for(String target in getBuildTargets(platform)) {
                    utils.chooseShellByPlatformMacWinLin([
                            "set -o pipefail && xcodebuild -scheme \"${target}\" -config \"${config}\" -project ${projectFile} build ${pipe_to_xcpretty}",
                            "\"${msBuild}\" /t:Build /m /p:Configuration=\"${config}\" /p:Platform=\"x64\" /p:ProductVersion=11.${env.BUILD_NUMBER} ${target}",
                            "cd design_linux && make -j\$(nproc) ${target}"
                    ], platform)
                }
            }

            if(utils.isWindows(platform)) {
                evSignWindows()
                buildAndArchiveShaders()
            }
        } catch (e) {
            String heyYourBuild = getSlackHeyYourBuild()
            String logUrl = "${BUILD_URL}flowGraphTable/"
            slackSend(
                    color: 'danger',
                    message: "${heyYourBuild} of `${branch_name}` failed | <${logUrl}|Console Log (split by machine/task/subtask)> | <${BUILD_URL}|Build Info>")
            alerted_via_slack = true
            notifyDeadBuild(utils.&sendEmail, 'X-Plane', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def evSignWindows() {
    if(utils.isReleaseBuild()) {
        for(String product : getProducts('Windows')) {
            if(product.toLowerCase().endsWith('.exe')) {
                evSignExecutable(product)
            }
        }
    }
}

def evSignExecutable(String executable) {
    List<String> timestampServers = [
            "http://timestamp.digicert.com",
            "http://sha256timestamp.ws.symantec.com/sha256/timestamp",
            "http://timestamp.globalsign.com/scripts/timstamp.dll",
            "https://timestamp.geotrust.com/tsa",
            "http://timestamp.verisign.com/scripts/timstamp.dll",
            "http://timestamp.comodoca.com/rfc3161",
            "http://timestamp.wosign.com",
            "http://tsa.startssl.com/rfc3161",
            "http://time.certum.pl",
            "https://freetsa.org",
            "http://dse200.ncipher.com/TSS/HttpTspServer",
            "http://tsa.safecreative.org",
            "http://zeitstempel.dfn.de",
            "https://ca.signfiles.com/tsa/get.aspx",
            "http://services.globaltrustfinder.com/adss/tsa",
            "https://tsp.iaik.tugraz.at/tsp/TspRequest",
            "http://timestamp.apple.com/ts01",
    ]
    withCredentials([string(credentialsId: 'windows-hardware-signing-token', variable: 'tokenPass')]) {
        for(String timestampServer : timestampServers) {
            // Joerg says: these servers occasionally get overloaded or go down, causing the signing to fail.
            // We'll try them all before returning an error
            try {
                bat "C:\\jenkins\\_signing\\etokensign.exe C:\\jenkins\\_signing\\laminar.cer \"te-10ee6b01-fc46-429c-a412-d6996404ebce\" \"${tokenPass}\" \"${timestampServer}\" \"${executable}\""
                return
            } catch(e) { }
        }
        throw Exception('etokensign failed for executable ' + executable)
    }
}

def buildAndArchiveShaders() {
    dir(utils.getCheckoutDir('Windows')) {
        String dropboxPath = utils.getArchiveDirAndEnsureItExists('Windows')
        String destSlashesEscaped = utils.escapeSlashes(dropboxPath)
        String shadersZip = 'shaders_bin.zip'
        if(!forceBuild && utils.copyBuildProductsFromArchive([shadersZip], 'Windows')) {
            echo 'Skipping shaders build since they already exist in Dropbox'
        } else {
            try {
                bat 'scripts\\shaders\\gfx-cc.exe Resources/shaders/master/input.json -o ./Resources/shaders/bin --fast -Os --quiet'
            } catch(e) {
                if(fileExists('gfx-cc.dmp')) {
                    archiveWithDropbox(['gfx-cc.dmp'], dropboxPath, false, utils)
                } else {
                    echo 'Failed to find gfx-cc.dmp'
                }
                throw e
            }
            zip(zipFile: shadersZip, archive: false, dir: 'Resources/shaders/bin/')
        }
        archiveWithDropbox([shadersZip], dropboxPath, true, utils)
    }
}

def doUnitTest(String platform) {
    if(supportsCatch2Tests(platform) && products_to_build.contains('TEST')) {
        dir(utils.getCheckoutDir(platform)) {
            String exe = getCatch2Executable(platform)
            if(utils.isMac(platform)) {
                exe += '/Contents/MacOS/catch2_tests' + utils.app_suffix
            }
            String xml = testXmlTarget(platform)
            try {
                utils.chooseShellByPlatformNixWin("./${exe} -r junit -o ${xml}", "${exe} /r junit /o ${xml}", platform)
                archiveWithDropbox([xml], utils.getArchiveDirAndEnsureItExists(platform), true, utils, false)
            } catch(e) {
                String heyYourBuild = getSlackHeyYourBuild()
                String logUrl = "${BUILD_URL}flowGraphTable/"
                slackSend(
                        color: 'danger',
                        message: "${heyYourBuild} of `${branch_name}` compiled, but it failed unit testing | <${BUILD_URL}|Build Info>")
                alerted_via_slack = true
            }
            junit keepLongStdio: true, testResults: xml
        }
    }
}

boolean needsInstallerKitting(String platform='') {
    return products_to_build.contains('INS') && isReleaseBuild() && !isSteamBuild() && isNix(platform)
}

def doArchive(String platform) {
    try {
        def checkoutDir = utils.getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = utils.getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "find . -name '*.app' -exec zip -rq '{}'.zip '{}' \\;"
                sh "find . -name '*.dSYM' -exec zip -rq '{}'.zip '{}' \\;"
            }

            List prods = getProducts(platform)
            // Kit the installers for deployment
            if(needsInstallerKitting(platform)) {
                String installer = getProducts(platform, true).find { el -> el.contains('Installer') }.replace('.zip', '') // takes the first match
                String zip_target = utils.chooseByPlatformMacWinLin(['X-Plane11InstallerMac.zip', 'X-Plane11InstallerWindows.zip', 'X-Plane11InstallerLinux.zip'], platform)
                utils.chooseShellByPlatformMacWinLin([
                        "zip -rq ${zip_target} \"${installer}\"",
                        "zip -j ${zip_target} \"${installer}\"",
                        "cp \"${installer}\" \"X-Plane 11 Installer Linux\" && zip -jq ${zip_target} \"X-Plane 11 Installer Linux\" && rm \"X-Plane 11 Installer Linux\"",
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
    boolean buildTriggeredByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
    if(buildTriggeredByUser && send_emails) {
        utils.sendEmail("Re: ${branch_name} build", "SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded.")
    }
    String productsUrl = "${BUILD_URL}artifact/*zip*/archive.zip"
    String heyYourBuild = getSlackHeyYourBuild()
    if(!heyYourBuild.contains('Autotriggered')) {
        try {
            slackSend(
                    color: 'good',
                    message: "${heyYourBuild} of ${branch_name} succeeded | <${productsUrl}|Download products> | <${BUILD_URL}|Build Info>")
            alerted_via_slack = true
        } catch(e) { }
    }
}

String getSlackHeyYourBuild() {
    try {
        def userCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
        if(userCause != null) {
            String slackUserId = jenkinsToSlackUserId(userCause.getUserId())
            if(slackUserId.isEmpty()) {
                return 'Manual build'
            } else {
                return "Hey <@${slackUserId}>, your build"
            }
        }
    } catch(e) { }
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
