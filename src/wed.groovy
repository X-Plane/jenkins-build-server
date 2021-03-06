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
repoSource = params.repo_source ? params.repo_source : 'public'
environment['override_checkout_dir'] = repoSource == 'next' ? 'xptools-next' : 'xptools'
toolchain_version = params.toolchain == '2020' ? 2020 : 2016
environment['toolchain_version'] = toolchain_version
utils.setEnvironment(environment, this.&notify, this.steps)

shouldPublish = params.publish_as_version && params.publish_as_version.length() >= 5 && params.publish_as_version.length() <= 6
assert repoSource == 'public' || !shouldPublish, "Can't publish from the non-public repo"

try {
    lock(extra: utils.resourcesToLock()) {
        stage('Checkout') { runOn3Platforms(this.&doCheckout) }
        stage('Build & Archive') { runOn3Platforms(this.&doBuildAndArchive) }
    }
    stage('Finalize Upload') { node('linux') { finalizeUpload('Linux') } }
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
    clean([getWedExe(platform), '*.zip', '*.WorldEditor', '*.exe'], [], platform, utils)
    if(utils.isWindows(platform)) {
        dir(utils.getCheckoutDir(platform)) {
            try {
                bat(returnStdout: true, script: "rd /s /q msvc")
            } catch(e) {}
        }
    }

    fileOperations([folderDeleteOperation(getPublishableZipName(platform))])
    try {
        repo = repoSource == 'next' ? 'https://github.com/meikelm/xptools-next.git' : 'https://github.com/X-Plane/xptools.git'
        xplaneCheckout(branch_name, utils.getCheckoutDir(platform), platform, repo)

        dir(utils.getCheckoutDir(platform)) {
            utils.chooseShell('git submodule foreach --recursive git reset --hard', platform)
        }
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'WED', branch_name, platform, e)
    }
}

def doBuildAndArchive(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        // Michael says the stock GCC 9 has issues building stuff compatible with 16.04 and 18.04; use GCC 7 instead
        String setLinGcc = toolchain_version == 2020 && utils.isLinux(platform) ? "CC=gcc-7 CXX=g++-7" : ""

        if(utils.isNix(platform)) {
            dir('libs') {
                if(utils.toRealBool(clean_libs)) {
                    sh "${setLinGcc} make clean"
                }
                sh "${setLinGcc} make"
            }
        }

        String exe = getWedExe(platform)
        String exePath = utils.addPrefix([exe], utils.chooseByPlatformMacWinLin(['', 'msvc\\WorldEditor\\Release\\', 'build/Linux/release_opt/'], platform))[0]

        try {
            String projectFile = utils.chooseByPlatformNixWin("SceneryTools.xcodeproj", "msvc\\XPTools.sln", platform)
            String xcodebuildBoilerplate = "set -o pipefail && xcodebuild -scheme WED -config Release -project ${projectFile}"
            String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
            String msBuild = '"C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Community\\MSBuild\\Current\\Bin\\MSBuild.exe"'
            if(utils.toRealBool(clean_build)) {
                utils.chooseShellByPlatformMacWinLin([
                        "${xcodebuildBoilerplate} clean ${pipe_to_xcpretty} && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                        "${msBuild} ${projectFile} /t:Clean",
                        "${setLinGcc} make clean"
                ], platform)
            }

            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} -archivePath WED.xcarchive  CODE_SIGN_STYLE=\"Manual\" CODE_SIGN_IDENTITY=\"Developer ID Application: Laminar Research (LPH4NFE92D)\" archive ${pipe_to_xcpretty}",
                    "${msBuild} /t:WorldEditor /m /p:Configuration=\"Release\" /p:Platform=\"x64\" ${projectFile}",
                    "${setLinGcc} make -s -C . conf=release_opt -j\$(nproc) WED"
            ], platform)

            if(shouldPublish && utils.isWindows(platform)) {
                withCredentials([string(credentialsId: 'windows-hardware-signing-token', variable: 'tokenPass')]) {
                    utils.evSignExecutable(exePath)
                }
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'WED', branch_name, utils.getCommitId(platform), platform, e)
        }

        String readme = 'README.WorldEditor'
        String targetZipName = getPublishableZipName(platform)
        String targetZip = "${targetZipName}.zip"

        try {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "pushd WED.xcarchive/Products/Applications/ && zip -qr ../../../${targetZip} WED.app && popd"
                sh "zip -qj ${targetZip} src/WEDCore/${readme}"
            } else if(utils.isLinux(platform)) {
                sh "zip -qrj ${targetZip} ${exePath} src/WEDCore/${readme}"
            } else {
                // Move the EXE to the root directory so that the final ZIP will be "flat"
                try {
                    bat "rd /s /q \"${targetZipName}\""
                } catch(e) { }
                utils.chooseShell("mkdir ${targetZipName}", platform)
                utils.copyFilePatternToDest(exePath, "${targetZipName}\\${exe}")
                utils.copyFilePatternToDest("src\\WEDCore\\${readme}", "${targetZipName}\\${readme}")
                zip(zipFile: targetZip, archive: false, dir: targetZipName)
            }
            archiveWithDropbox([targetZip], getArchiveDirAndEnsureItExists(platform, 'WED'), true, utils, false)
        } catch (e) {
            utils.sendEmail("WED archive step failed on ${platform} [${branch_name}]",
                    "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing the WED executable.",
                    e.toString())
            throw e
        }

        if(shouldPublish) { // we want to copy it to the live server
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
    if(shouldPublish) { // we copied files to the live server
        sshagent(['tylers-ssh']) {
            for(def p : utils.platforms()) {
                def zipName = getPublishableZipName(p)
                sh "ssh tyler@dev.x-plane.com chmod o+r /shared/download/tools/${zipName}.zip"
            }
        }
    }
}

String getWedExe(String platform) {
    return utils.chooseByPlatformMacWinLin(['WED.app.zip', 'WorldEditor.exe', 'WED'], platform)
}

String getPublishableZipName(String platform) {
    String shortPlatform = utils.chooseByPlatformMacWinLin(['mac', 'win', 'lin'], platform)
    return "wed_${shortPlatform}_${params.publish_as_version}"
}

String getArchiveDirAndEnsureItExists(String platform='', String optionalSubdir='') {
    String out = utils.getArchiveDir(platform, optionalSubdir)
    fileOperations([folderCreateOperation(out)])
    return out
}



