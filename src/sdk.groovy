def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = ''
environment['directory_suffix'] = ''
environment['pmt_from'] = ''
environment['release_build'] = 'true'
environment['build_windows'] = params.platforms.contains('windows') ? 'true' : 'false'
environment['build_mac'] = params.platforms.contains('mac') ? 'true' : 'false'
environment['build_linux'] = params.platforms.contains('linux') ? 'true' : 'false'
environment['dev_build'] = 'false'
toolchain_version = params.toolchain == '2020' ? 2020 : 2016
environment['toolchain_version'] = toolchain_version
utils.setEnvironment(environment, this.&notify, this.steps)
assert utils.build_mac || build_type != 'build_dlls'

try {
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout) }
    if(build_type == 'build_dlls') {
        stage('Build')                     { runOn3Platforms(this.&doBuild) }
        stage('Archive')                   { runOn3Platforms(this.&archiveBuild) }
    } else {
        stage('Build')                     { node('mac' + toolchain_version) { packageRelease('macOS') } }
        stage('Archive')                   { node('mac' + toolchain_version) { archiveRelease('macOS') } }
    }
    stage('Notify')                        { utils.replyToTrigger("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded.") }
} finally {
    node('master') {
        String parseRulesUrl = 'https://raw.githubusercontent.com/X-Plane/jenkins-build-server/master/log-parser-builds.txt'
        utils.chooseShellByPlatformNixWin("curl ${parseRulesUrl} -O", "C:\\msys64\\usr\\bin\\curl.exe ${parseRulesUrl} -O")
        step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: "${pwd()}/log-parser-builds.txt", useProjectRule: false])
    }
}


def runOn3Platforms(Closure c) {
    def closure = c
    parallel (
            'Windows' : { if(utils.build_windows) { node('windows' + toolchain_version) { closure('Windows') } } },
            'macOS'   : { if(utils.build_mac)     { node('mac' + toolchain_version)     { closure('macOS')   } } },
            'Linux'   : { if(utils.build_linux)   { node('linux' + toolchain_version)   { closure('Linux')   } } }
    )
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
            if(utils.isWindows(platform)) { // Windows needs *two* solutions cleaned & built
                String msBuild = utils.isWindows(platform) ? (toolchain_version == 2020 ? "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Community\\MSBuild\\Current\\Bin\\MSBuild.exe" : "${tool 'MSBuild'}") : ''
                bat "\"${msBuild}\" XPLM.sln      /t:Clean"
                bat "\"${msBuild}\" XPWidgets.sln /t:Clean"
                bat "\"${msBuild}\" /t:XPLM      /m /p:Configuration=\"Release\" XPLM.sln"
                bat "\"${msBuild}\" /t:XPWidgets /m /p:Configuration=\"Release\" XPWidgets.sln"
            } else if(utils.isMac(platform)) {
                def xcodebuildBoilerplate = "set -o pipefail && xcodebuild -target \"Build All\" -config Release -project XPLM.xcodeproj"
                def pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
                sh "${xcodebuildBoilerplate} clean ${pipe_to_xcpretty}"
                sh "${xcodebuildBoilerplate} build ${pipe_to_xcpretty}"
            } else {
                sh 'make clean'
                sh 'make XPLM XPWidgets'
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'XPLM', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def packageRelease(String platform) {
    dir(getSdkCheckoutDir(platform) + 'Src') {
        def copyFrom = getArchiveDirAndEnsureItExists(platform)
        sh "./MakeSDK.sh ${copyFrom}"
    }
}

def archiveBuild(String platform) {
    try {
        def checkoutDir = getSdkCheckoutDir(platform)
        dir(checkoutDir) {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                dir(checkoutDir + 'Build/Mac/build/Release/') {
                    sh 'zip -r XPLM.framework.zip XPLM.framework'
                    sh 'zip -r XPWidgets.framework.zip XPWidgets.framework'
                }
            }
            def productPaths = []
            if(utils.isNix(platform)) {
                productPaths = utils.addPrefix(getExpectedSdkProducts(platform), utils.chooseByPlatformMacWinLin(['Build/Mac/build/Release/', 'N/A!', 'Build/Linux/build/'], platform))
            } else {
                // Irritatingly, Windows products go into *two* different directories
                def products = getExpectedSdkProducts(platform)
                def libs = getWindowsLibs()
                for(String p : products) {
                    if(libs.contains(p)) {
                        productPaths.push('Build\\Win\\Release\\' + p)
                    } else {
                        productPaths.push('Build\\Win\\Release\\plugins\\' + p)
                    }
                }
            }

            archiveWithDropbox(productPaths, getArchiveDirAndEnsureItExists(platform), true, utils)
        }
    } catch (e) {
        utils.sendEmail("WED archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing the WED executable.",
                e.toString())
        throw e
    }
}

def archiveRelease(String platform) {
    dir(getSdkCheckoutDir(platform)) {
        sh 'zip -r XPLM.zip SDK'
        archiveWithDropbox(['XPLM.zip'], getArchiveDirAndEnsureItExists(platform), true, utils)
    }
}

List<String> getExpectedSdkProducts(String platform) {
    return utils.chooseByPlatformMacWinLin([
            ['XPLM.framework.zip', 'XPWidgets.framework.zip'],
            ['XPLM_64.dll', 'XPLM_64.pdb', 'XPWidgets_64.dll', 'XPWidgets_64.pdb'] + getWindowsLibs(),
            ['XPLM_64.so', 'XPWidgets_64.so']
    ], platform)
}
List<String> getWindowsLibs() {
    return ['XPLM_64.lib', 'XPWidgets_64.lib']
}
String getSdkCheckoutDir(String platform) {
    if(utils.isWindows(platform)) {
        return"C:\\jenkins\\xplanesdk\\"
    } else if(fileExists('/Users/Shared/jenkins')) {
        return '/Users/Shared/jenkins/xplanesdk/'
    } else {
        return '/jenkins/xplanesdk/'
    }
}

String getArchiveDirAndEnsureItExists(String platform) {
    def out = utils.getArchiveDir(platform, 'SDK')
    try {
        utils.chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"")
    } catch(e) { } // ignore errors if it already exists
    return out
}



