def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = ''
environment['directory_suffix'] = ''
environment['pmt_from'] = ''
environment['release_build'] = 'true'
environment['build_windows'] = build_windows
environment['build_mac'] = build_mac
environment['build_linux'] = build_linux
environment['dev_build'] = 'false'
environment['override_checkout_dir'] = 'xptools'
utils.setEnvironment(environment, this.&notify, this.steps)

try {
    stage('Checkout') { runOn3Platforms(this.&doCheckout) }
    stage('Build & Archive') { runOn3Platforms(this.&doBuildAndArchive)    }
} finally {
    node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
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
    clean(getExpectedWedProducts(platform) + ['*.zip', '*.WorldEditor', getPublishableZipName(platform) + '*'], [], platform, utils)
    try {
        xplaneCheckout(branch_name, utils.getCheckoutDir(platform), platform, 'https://github.com/X-Plane/xptools.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'WED', branch_name, platform, e)
    }
}

def doBuildAndArchive(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            String projectFile = utils.chooseByPlatformNixWin("SceneryTools.xcodeproj", "msvc\\XPTools.sln", platform)
            String xcodebuildBoilerplate = "set -o pipefail && xcodebuild -scheme WED -config Release -project ${projectFile}"
            String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
            String msBuild = utils.isWindows(platform) ? "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild" : ''
            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} clean ${pipe_to_xcpretty} && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                    "\"${msBuild}\" ${projectFile} /t:Clean",
                    'make clean'
            ], platform)

            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} -archivePath WED.xcarchive archive ${pipe_to_xcpretty}",
                    "\"${msBuild}\" /t:WorldEditor /m /p:Configuration=\"Release\" /p:Platform=\"x64\" ${projectFile}",
                    "make -s -C . conf=release_opt -j\$(nproc) WED"
            ], platform)
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'WED', branch_name, utils.getCommitId(platform), platform, e)
        }

        List<String> expectedProducts = getExpectedWedProducts(platform);
        List<String> productPaths = utils.addPrefix(expectedProducts, utils.chooseByPlatformMacWinLin(['', 'msvc\\WorldEditor\\Release\\', 'build/Linux/release_opt/'], platform))

        try {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                zip(zipFile: 'WED.app.zip', archive: false, dir: 'WED.xcarchive/Products/Applications/')
            }
            archiveWithDropbox(productPaths, getArchiveDirAndEnsureItExists(platform, 'WED'), true, utils, false)
        } catch (e) {
            utils.sendEmail("WED archive step failed on ${platform} [${branch_name}]",
                    "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing the WED executable.",
                    e.toString())
            throw e
        }

        if(publish_as_version && publish_as_version.length() == 5) { // we want to copy it to the live server
            String targetZipName = getPublishableZipName(platform)
            String targetZip = "${targetZipName}.zip"

            String readme = 'README.WorldEditor'

            if(utils.isMac(platform)) {
                utils.copyFilePatternToDest(productPaths.first(), targetZip)
                sh "zip -j ${targetZip} src/WEDCore/${readme}"
            } else {
                // Move the EXE to the root directory so that the final ZIP will be "flat"
                utils.chooseShell("mkdir ${targetZipName}", platform)
                utils.copyFilePatternToDest(productPaths.first(), "${targetZipName}/${expectedProducts.first()}")
                utils.copyFilePatternToDest("src/WEDCore/${readme}", "${targetZipName}/${readme}")
                zip(zipFile: targetZip, archive: false, dir: targetZipName)
            }
            sshPublisher(publishers: [
                    sshPublisherDesc(
                            configName: 'DevTools',
                            transfers: [sshTransfer(sourceFiles: targetZip, execCommand: "chmod o+r ${targetZip}")],
                    )
            ])
        }
    }
}

List<String> getExpectedWedProducts(String platform) {
    return [utils.chooseByPlatformMacWinLin(['WED.app.zip', 'WorldEditor.exe', 'WED'], platform)]
}

String getPublishableZipName(String platform) {
    String shortPlatform = utils.chooseByPlatformMacWinLin(['mac', 'win', 'lin'], platform)
    return "wed_${shortPlatform}_${publish_as_version}"
}

String getArchiveDirAndEnsureItExists(String platform='', String optionalSubdir='') {
    String out = utils.getArchiveDir(platform, optionalSubdir)
    fileOperations([folderCreateOperation(out)])
    return out
}



