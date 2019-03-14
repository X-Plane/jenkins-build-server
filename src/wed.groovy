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
    stage('Finalize Upload') { node('linux') { finalizeUpload('Linux') }   }
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
    clean([getWedExe(platform), '*.zip', '*.WorldEditor'], [], platform, utils)
    fileOperations([folderDeleteOperation(getPublishableZipName(platform))])
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


        String exe = getWedExe(platform)
        String exePath = utils.addPrefix([exe], utils.chooseByPlatformMacWinLin(['', 'msvc\\WorldEditor\\Release\\', 'build/Linux/release_opt/'], platform))[0]
        String readme = 'README.WorldEditor'
        String targetZipName = getPublishableZipName(platform)
        String targetZip = "${targetZipName}.zip"

        try {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "zip -qr ${targetZip} WED.xcarchive/Products/Applications/*"
                sh "zip -qj ${targetZip} src/WEDCore/${readme}"
            } else if(utils.isLinux(platform)) {
                sh "zip -qrj ${targetZip} ${exePath} src/WEDCore/${readme}"
            } else {
                // Move the EXE to the root directory so that the final ZIP will be "flat"
                try {
                    bat "rd /s /q \"${targetZipName}\""
                } catch(e) { }
                utils.chooseShell("mkdir ${targetZipName}", platform)
                utils.copyFilePatternToDest(productPaths.first(), "${targetZipName}/${exe}")
                utils.copyFilePatternToDest("src/WEDCore/${readme}", "${targetZipName}/${readme}")
                zip(zipFile: targetZip, archive: false, dir: targetZipName)
            }
            archiveWithDropbox([targetZip], getArchiveDirAndEnsureItExists(platform, 'WED'), true, utils, false)
        } catch (e) {
            utils.sendEmail("WED archive step failed on ${platform} [${branch_name}]",
                    "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing the WED executable.",
                    e.toString())
            throw e
        }

        if(publish_as_version && publish_as_version.length() == 5) { // we want to copy it to the live server
            sshPublisher(publishers: [
                    sshPublisherDesc(
                            configName: 'DevTools',
                            transfers: [sshTransfer(sourceFiles: targetZip)],
                    )
            ])
        }
    }
}

def finalizeUpload(String platform) {
    if(publish_as_version && publish_as_version.length() == 5) { // we copied files to the live server
        for(def p : ['macOS', 'Windows', 'Linux']) {
            def zipName = getPublishableZipName(p)
            sh "ssh dev.x-plane.com chmod o+r /shared/download/tools/${zipName}.zip"
        }
    }
}

String getWedExe(String platform) {
    return utils.chooseByPlatformMacWinLin(['WED.app.zip', 'WorldEditor.exe', 'WED'], platform)
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



