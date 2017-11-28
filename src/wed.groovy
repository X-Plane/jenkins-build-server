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
environment['dev_build'] = dev_build
utils.setEnvironment(environment, this.&notify, this.steps)

try {
    utils.do3PlatformStage('Checkout', this.&doCheckout)
    utils.do3PlatformStage('Build',    this.&doBuild)
    utils.do3PlatformStage('Archive',  this.&doArchive)
} finally {
    node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
}

def doCheckout(String platform) {
    clean(getExpectedWedProducts(platform), [], platform, utils)
    try {
        xplaneCheckout(branch_name, getWedCheckoutDir(platform), platform, 'https://github.com/X-Plane/xptools.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'WED', branch_name, platform, e)
    }
}

def doBuild(String platform) {
    dir(getWedCheckoutDir(platform)) {
        try {
            def projectFile = utils.chooseByPlatformNixWin("SceneryTools_xcode6.xcodeproj", "msvc\\XPTools.sln", platform)
            def xcodebuildBoilerplate = "set -o pipefail && xcodebuild -target WED -config Release -project ${projectFile}"
            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} clean | xcpretty",
                    "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                    'make clean'
            ], platform)

            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} build | xcpretty",
                    "\"${tool 'MSBuild'}\" /t:WorldEditor /m /p:Configuration=\"Release\" ${projectFile}",
                    "make -s -C . conf=release_opt WED"
            ], platform)
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'WED', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def doArchive(String platform) {
    try {
        dir(getWedCheckoutDir(platform)) {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh 'zip -r WED.app.zip WED.xcarchive/Products/Applications/WED.app'
            }
            def productPaths = utils.addPrefix(getExpectedWedProducts(platform), utils.chooseByPlatformMacWinLin(['', 'msvc\\WorldEditor\\', 'build/Linux/release_opt/'], platform))
            archiveWithDropbox(productPaths, utils.getArchiveDirAndEnsureItExists(platform, 'WED'), true, utils)
        }
    } catch (e) {
        utils.sendEmail("WED archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing the WED executable.",
                e.toString())
        throw e
    }
}

List<String> getExpectedWedProducts(String platform) {
    return [utils.chooseByPlatformMacWinLin(['WED.app.zip', 'WorldEditor.exe', 'WED'], platform)]
}
String getWedCheckoutDir(String platform) {
    return utils.chooseByPlatformNixWin("/jenkins/xptools/", "C:\\jenkins\\xptools\\", platform)
}



