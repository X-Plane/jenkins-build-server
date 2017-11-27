def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = ''
environment['directory_suffix'] = ''
environment['pmt_from'] = ''
environment['release_build'] = release_build
environment['build_windows'] = build_windows
environment['build_mac'] = build_mac
environment['build_linux'] = build_linux
utils.setEnvironment(environment, this.&notify, this.steps)

try {
    utils.do3PlatformStage('Checkout', this.&doCheckout)
    utils.do3PlatformStage('Build',    this.&doBuild)
    utils.do3PlatformStage('Archive',  this.&doArchive)
} finally {
    node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
}

def doCheckout(String platform) {
    clean(getExpectedSdkProducts(platform), [], platform, utils)
    try {
        xplaneCheckout(branch_name, getSdkCheckoutDir(platform), platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/xplanesdk.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'SDK', branch_name, platform, e)
    }
}

def doBuild(String platform) {
    String buildDir = utils.chooseByPlatformMacWinLin(['Build/Mac/', "Build\\Win", 'Build/Linux/'], platform)
    dir(getSdkCheckoutDir(platform) + buildDir) {
        try {
            def projectFile = utils.chooseByPlatformNixWin("XPLM.xcodeproj", "XPLM.sln", platform)
            def xcodebuildBoilerplate = "set -o pipefail && xcodebuild -target XPLM -config Release -project ${projectFile}"
            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} clean | xcpretty",
                    "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                    'make clean'
            ], platform)

            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} build | xcpretty",
                    "\"${tool 'MSBuild'}\" /t:XPLM /m /p:Configuration=\"Release\" ${projectFile}",
                    "make XPLM"
            ], platform)
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'XPLM', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def doArchive(String platform) {
    try {
        dir(getSdkCheckoutDir(platform)) {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh 'cd Build/Mac/build/Release/ && zip -r XPLM.framework.zip XPLM.framework'
            }
            def productPaths = utils.addPrefix(getExpectedSdkProducts(platform), utils.chooseByPlatformMacWinLin(['Build/Mac/build/Release/', 'Build\\Win\\Release\\plugins\\', 'Build/Linux/build/'], platform))
            archiveWithDropbox(productPaths, getArchiveDirAndEnsureItExists(platform), true, utils)
        }
    } catch (e) {
        utils.sendEmail("WED archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing the WED executable.",
                e.toString())
        throw e
    }
}

List<String> getExpectedSdkProducts(String platform) {
    List<String> out = [utils.chooseByPlatformMacWinLin(['XPLM.framework.zip', 'XPLM_64.dll', 'XPLM_64.so'], platform)]
    if(utils.isWindows(platform)) {
        out.push('XPLM_64.pdb')
    }
    return out
}
String getSdkCheckoutDir(String platform) {
    return utils.chooseByPlatformNixWin("/jenkins/xplanesdk/", "C:\\jenkins\\xplanesdk\\", platform)
}

String getArchiveDirAndEnsureItExists(String platform) {
    def out = utils.getArchiveDir(platform, 'SDK')
    try {
        utils.chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"")
    } catch(e) { } // ignore errors if it already exists
    return out
}



