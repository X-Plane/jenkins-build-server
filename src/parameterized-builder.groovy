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
utils.setEnvironment(environment, this.&notify, this.steps)

alerted_via_slack = false
doClean = utils.toRealBool(clean_build)
forceBuild = utils.toRealBool(force_build)
wantShaders = products_to_build.contains('SHADERS')

assert sanitizer != 'undefined-behavior', "Sorry, neither our Mac nor our Ubuntu builders support UBsan... wait for the compiler upgrade!"

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
        } else {
            parallel (
                    // gotta handle shaders specially; we can do this on Windows in parallel with the other platforms (win!)
                    'Windows' : { if(wantShaders)         { node('windows') { buildAndArchiveShaders() } } },
                    'macOS'   : { if(utils.build_mac)     { node('mac')     { timeout(60 * 2) { doBuild('macOS')   } } } },
                    'Linux'   : { if(utils.build_linux)   { node('linux')   { timeout(60 * 2) { doBuild('Linux')   } } } }
            )
        }
    }
    stage('Unit Test')                     { runOn3Platforms(this.&doUnitTest) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
    stage('Notify') {
        if(!alerted_via_slack) { notifySuccess() }
        jiraSendBuildInfo(branch: branch_name, site: 'x-plane.atlassian.net')
    }
} finally {
    node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/jenkins-build-server/log-parser-builds.txt', useProjectRule: false]) }
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
    if(utils.isSteamBuild()) {
        return false
    }

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
            appNamesForWinSymbols.push(nameWithSuffix)
        }
    }

    boolean needsSymbols = !ignoreSymbols && build_type.contains('NODEV_OPT_Prod')
    if(needsSymbols) {
        def symbolsSuffix = utils.chooseByPlatformMacWinLin(['.app.dSYM.zip', '_win.sym', '_lin.sym'], platform)
        List<String> macAppsWithSymbols = products_to_build.contains('SIM') ? ['X-Plane'] : []
        def platformSymbols = utils.addSuffix(utils.chooseByPlatformMacWinLin([macAppsWithSymbols, appNamesForWinSymbols, filesWithExt], platform), symbolsSuffix)
        filesWithExt += platformSymbols
    }

    boolean needsWinPdb = !ignoreSymbols && utils.isWindows(platform) && (build_type.contains('NODEV_OPT_Prod') || utils.toRealBool(want_windows_pdb))
    if(needsWinPdb) {
        filesWithExt += utils.addSuffix(appNamesForWinSymbols, ".pdb")
    }
    return filesWithExt
}

// Docs here: https://github.com/jenkinsci/file-operations-plugin
def nukeFolders(List<String> paths) { fileOperations(paths.collect { folderDeleteOperation(it) }) }
def nukeFolder(      String  path ) { fileOperations([folderDeleteOperation(path)]) }
def nukeFiles(  List<String> files) { fileOperations(files.collect { fileDeleteOperation(includes: it) }) }
def nukeFile(        String  file ) { fileOperations([fileDeleteOperation(includes: file)]) }

def doCheckout(String platform) {
    String checkoutDir = utils.getCheckoutDir(platform)
    dir(checkoutDir) {
        // Nuke previous products
        nukeFolder(utils.chooseByPlatformMacWinLin(['design_xcode', 'design_vstudio', 'design_linux'], platform))
        List<String> to_nuke = getProducts(platform) + [testXmlTarget(platform)]
        if(utils.isMac(platform)) {
            to_nuke.push('*.app')
        }
        clean(to_nuke, null, platform, utils)

        if(doClean && wantShaders) {
            String shaderDir = utils.chooseByPlatformNixWin('Resources/shaders/bin/', 'Resources\\shaders\\bin\\', platform)
            nukeFolders(utils.addPrefix(['glsl120', 'glsl130', 'glsl150', 'spv', 'mlsl'], shaderDir))
        }
        nukeFiles(['*.zip'])

        try {
            xplaneCheckout(branch_name, checkoutDir, platform)
            if(products_to_build.contains('TEST') && utils.shellIsSh(platform)) {
                getArt(checkoutDir)
            }
        } catch(e) {
            notifyBrokenCheckout(utils.&sendEmail, 'X-Plane', branch_name, platform, e)
            if(!alerted_via_slack) {
                alerted_via_slack = slackBuildInitiatorFailure("failed to check out `${branch_name}` | <${BUILD_URL}flowGraphTable/|Log (split by machine & task)>")
            }
        }
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
            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            def toBuild = getProducts(platform)
            echo 'Expecting to build: ' + toBuild.join(', ')
            if(!forceBuild && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                String config = utils.getBuildToolConfiguration()

                // Generate our project files
                String sanitizerArg = getSanitizerShellArg(platform)
                utils.chooseShellByPlatformMacWinLin(["./cmake.sh --no_gfxcc ${sanitizerArg}", 'cmd /C ""%VS140COMNTOOLS%vsvars32.bat" && cmake.bat --no_gfxcc"', "./cmake.sh ${config} --no_gfxcc ${sanitizerArg}"], platform)

                String projectFile = utils.chooseByPlatformNixWin("design_xcode/X-System.xcodeproj", "design_vstudio\\X-System.sln", platform)

                String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
                String msBuild = utils.isWindows(platform) ? "${tool 'MSBuild'}" : ''

                if(doClean) {
                    if(utils.isMac(platform)) {
                        sh "rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*"
                        sh "xcodebuild -project ${projectFile} clean"
                        sh "xcodebuild -scheme \"ALL_BUILD\" -config \"${config}\" -project ${projectFile} clean"
                    } else {
                        utils.chooseShellByPlatformNixWin(
                                'cd design_linux && make clean',
                                "\"${msBuild}\" ${projectFile} /t:Clean", platform)
                    }
                }

                for(String target in getBuildTargets(platform)) {
                    utils.chooseShellByPlatformMacWinLin([
                            "set -o pipefail && xcodebuild -target \"${target}\" -config \"${config}\" -project ${projectFile} ${getMacSanitizerBuildArg()} build ${pipe_to_xcpretty}",
                            "\"${msBuild}\" /t:Build /m /p:Configuration=\"${config}\" /p:Platform=\"x64\" /verbosity:minimal /p:ProductVersion=11.${env.BUILD_NUMBER} ${target}",
                            "cd design_linux && make -j\$(nproc) ${target}"
                    ], platform)
                }
            }

            if(utils.isWindows(platform)) {
                evSignWindows()

                if(wantShaders) {
                    buildAndArchiveShaders()
                }
            }
        } catch (e) {
            String heyYourBuild = getSlackHeyYourBuild()
            slackSend(
                    color: 'danger',
                    message: "${heyYourBuild} of `${branch_name}` failed on ${platform} | <${BUILD_URL}parsed_console/|Parsed Console Log> | <${BUILD_URL}flowGraphTable/|Log (split by machine & task)>")
            alerted_via_slack = true
            notifyDeadBuild(utils.&sendEmail, 'X-Plane', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def getSanitizerShellArg(String platform) {
    if(sanitizer == 'address') {
        return '--asan'
    } else if(sanitizer == 'thread' && !utils.isLinux(platform)) {
        return '--tsan'
    } else if(sanitizer == 'undefined-behavior') {
        return '--ubsan'
    } else {
        return ''
    }
}

def getMacSanitizerBuildArg() {
    if(sanitizer == 'address') {
        return '-enableAddressSanitizer YES'
    } else if(sanitizer == 'thread') {
        return '-enableThreadSanitizer YES'
    } else if(sanitizer == 'undefined-behavior') {
        return '-enableUndefinedBehaviorSanitizer YES'
    } else {
        return ''
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
        String shadersZip = 'shaders_bin.zip'
        String dropboxPath = getArchiveDirAndEnsureItExists('Windows')

        // Need to hash both the shader source files and gfx-cc itself
        String allHashes = powershell(returnStdout: true, script: 'Get-FileHash -Path .\\Resources\\shaders\\**\\*.xsv  | Select -ExpandProperty Hash') +
                           powershell(returnStdout: true, script: 'Get-FileHash -Path .\\Resources\\shaders\\**\\*.glsl | Select -ExpandProperty Hash') +
                           powershell(returnStdout: true, script: 'Get-FileHash -Path .\\scripts\\shaders\\gfx-cc.exe   | Select -ExpandProperty Hash')
        String combinedHash = powershell(returnStdout: true, script: "\$StringBuilder = New-Object System.Text.StringBuilder ; [System.Security.Cryptography.HashAlgorithm]::Create(\"MD5\").ComputeHash([System.Text.Encoding]::UTF8.GetBytes(\"${allHashes}\"))|%{ ; [Void]\$StringBuilder.Append(\$_.ToString(\"x2\")) ; } ;  \$StringBuilder.ToString()").replaceAll("\\s","")

        String shaderCacheDir = utils.getArchiveRoot('Windows') + "shader_cache\\"
        fileOperations([folderCreateOperation(shaderCacheDir)])
        String shaderCachePath = "${shaderCacheDir}${combinedHash}.zip"
        boolean cacheExists = fileExists(shaderCachePath)
        if(!forceBuild && cacheExists) {
            echo "Skipping shaders build since they already exist in Dropbox (combined hash ${combinedHash})"
            utils.copyFilePatternToDest(shaderCachePath, shadersZip, 'Windows')
        } else {
            try {
                retry { bat 'scripts\\shaders\\gfx-cc.exe Resources/shaders/master/input.json -o ./Resources/shaders/bin --fast -Os --quiet' }
            } catch(e) {
                if(fileExists('gfx-cc.dmp')) {
                    archiveWithDropbox(['gfx-cc.dmp'], dropboxPath, false, utils)
                } else {
                    echo 'Failed to find gfx-cc.dmp'
                }
                echo 'ERROR: gfx-cc.exe crashed repeatedly; giving up on building shaders'
                throw e
            }
            zip(zipFile: shadersZip, archive: false, dir: 'Resources/shaders/bin/')
        }

        if(!cacheExists) {
            utils.copyFilePatternToDest(shadersZip, shaderCachePath)
        }
        archiveWithDropbox([shadersZip], dropboxPath, true, utils)
    }
}

def retry(Closure c, int max_tries=5) {
    def closure = c
    for(int i = 0; i < max_tries - 1; ++i) {
        try {
            return closure()
        } catch(e) { sleep(10) }
    }
    return closure()
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
            } catch(e) {
                String user = atSlackUser()
                if(user) {
                    user += ' '
                }
                slackSend(
                        color: 'danger',
                        message: "${user}`${branch_name}` failed unit testing (but did compile) | <${BUILD_URL}testReport/|Test Report>")
                alerted_via_slack = true
            }
            archiveWithDropbox([xml], getArchiveDirAndEnsureItExists(platform), true, utils, false)
            junit keepLongStdio: true, testResults: xml
        }
    }
}

boolean needsInstallerKitting(String platform='') {
    return products_to_build.contains('INS') && utils.isReleaseBuild() && !utils.isSteamBuild()
}

def getArchiveDirAndEnsureItExists(String platform, String optionalSubdir='') {
    String path = utils.getArchiveDir(platform, optionalSubdir)
    fileOperations([folderCreateOperation(path)])
    return path
}

def doArchive(String platform) {
    if(products_to_build != 'SHADERS') { // if we haven't already archived everything we needed!
        try {
            def checkoutDir = utils.getCheckoutDir(platform)
            dir(checkoutDir) {
                def dropboxPath = getArchiveDirAndEnsureItExists(platform)
                echo "Copying files from ${checkoutDir} to ${dropboxPath}"

                // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
                if(utils.isMac(platform)) {
                    sh "find . -name '*.app' -exec zip -rq --symlinks '{}'.zip '{}' \\;"
                    sh "find . -name '*.dSYM' -exec zip -rq '{}'.zip '{}' \\;"
                }

                List prods = getProducts(platform)

                if(sanitizer) { // rename the files so they don't get archived in Dropbox in a way that prevents us from building non-sanitized versions
                    for(String product : prods) {
                        utils.copyFile(product, renamedSanitizerProduct(product), platform)
                    }
                    prods = prods.collect { renamedSanitizerProduct(it) }
                } else if(needsInstallerKitting(platform)) {
                    String installer = getProducts(platform, true).find { el -> el.contains('Installer') } // takes the first match
                    String zipTarget = utils.chooseByPlatformMacWinLin(['X-Plane11InstallerMac.zip', 'X-Plane11InstallerWindows.zip', 'X-Plane11InstallerLinux.zip'], platform)
                    if(utils.isLinux(platform)) { // Gotta rename the installer to match what X-Plane's auto-runner expects... sigh...
                        String renamedInstaller = "X-Plane 11 Installer Linux"
                        utils.copyFile(installer, renamedInstaller, platform)
                        zip(zipFile: zipTarget, archive: false, glob: renamedInstaller)
                        nukeFile(renamedInstaller)
                    } else if(utils.isMac(platform)) { // Copy the ZIP we already made (with symlinks intact for code signing)
                        utils.copyFile(installer, zipTarget, platform)
                    } else {
                        zip(zipFile: zipTarget, archive: false, glob: installer)
                    }
                    prods.push(zipTarget)
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
}

def renamedSanitizerProduct(String originalName) {
    if(sanitizer) {
        return "${sanitizer}_sanitizer_${originalName}"
    }
    return originalName
}

def notifySuccess() {
    boolean buildTriggeredByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
    if(buildTriggeredByUser && send_emails) {
        utils.sendEmail("Re: ${branch_name} build", "SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded.")
    }
    if(!alerted_via_slack) {
        String productsUrl = "${BUILD_URL}artifact/*zip*/archive.zip"
        alerted_via_slack = slackBuildInitiatorSuccess("finished building `${branch_name}` | <${productsUrl}|Download products> | <${BUILD_URL}|Build Info>")
    }
}

