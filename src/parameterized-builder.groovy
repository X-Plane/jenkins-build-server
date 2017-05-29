try {
    if(pmt_subject && pmt_from) {
        stage('Respond')                   { replyToTrigger('Build started.\n\nThe automated build of commit ' + pmt_subject + ' is in progress.') }
    }
    stage('Ping Machines')                 { runOn3Platforms(this.&ping) }
    stage('Nuke Previous Build Products')  { runOn3Platforms(this.&nukePreviousBuildProducts) }
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout) }
    stage('Build')                         { runOn3Platforms(this.&doBuild) }
    stage('Test')                          { runOn3Platforms(this.&doTest) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
    if(pmt_subject && pmt_from) {
        stage('Notify')                    { replyToTrigger('SUCCESS!\n\nThe automated build of commit ' + pmt_subject + ' succeeded.') }
    }
} finally {
    node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'D:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
}

def runOn3Platforms(Closure c) {
    def closure = c
    parallel (
            'Windows' : { node('windows') { closure('Windows') } },
            'macOS'   : { node('mac')     { closure('macOS')   } },
            'Linux'   : { node('linux')   { closure('Linux')   } }
    )
}

def nukePreviousBuildProducts(String platform) {
    dir(getCheckoutDir(platform)) {
        try {
            def file_pattern = getAppPattern(platform)
            chooseShellByPlatformNixWin("rm -R ${file_pattern}", "del \"${file_pattern}\"", platform)
        } catch(e) { } // No old build products lying around? No problem!
        try {
                chooseShellByPlatformNixWin("rm *.png", "del \"*.png\"", platform)
        } catch(e) { } // No old screenshots lying around? No problem!
    }
}

def getBranchName() {
    if(pmt_subject) {
        return pmt_subject.trim()
    } else {
        return branch_name
    }
}

def doCheckout(String platform) {
    dir(getCheckoutDir(platform)) {
        def b = getBranchName()
        try {
            echo "Checking out ${b} on ${platform}"
            def extensions = [
                    [$class: 'BuildChooserSetting', buildChooser: [$class: 'AncestryBuildChooser', ancestorCommitSha1: '', maximumAgeInDays: 21]]
            ]
            checkout(
                    [$class: 'GitSCM', branches: [[name: b]],
                     doGenerateSubmoduleConfigurations: false,
                     extensions: extensions,
                     submoduleCfg: [],
                     userRemoteConfigs:  [[credentialsId: 'tylers-ssh', url: 'ssh://tyler@dev.x-plane.com/admin/git-xplane/design.git']]]
            )

            def commit_id = getCommitId(platform)
            echo "Checked out commit ${commit_id} on " + platform

            if(platform != 'Windows') {
                echo "Pulling SVN art assets too for later auto-testing"
                // Do recursive cleanup, just in case
                sh(returnStdout: true, script: "find Aircraft  -type d -exec \"svn cleanup\" \\;")
                sh(returnStdout: true, script: "find Resources -type d -exec \"svn cleanup\" \\;")
                sshagent(['tylers-ssh']) {
                    sh 'scripts/get_art.sh checkout tyler'
                }
            }
        } catch(e) {
            currentBuild.result = "FAILED"
            notifyBuild('Jenkins Git checkout is broken on ' + platform + ' [' + b + ']',
                    platform + ' Git checkout failed on branch ' + b + '. We will be unable to build until this is fixed.',
                    e.toString(),
                    'tyler@x-plane.com')
            throw e
        }
    }
}

def getCheckoutDir(String platform) {
    def nix = platform != 'Windows'
    return (nix ? '/jenkins/' : 'D:\\jenkins\\') + 'design-' + directory_suffix + (nix ? '/' : '\\')
}

def getCommitId(String platform) {
    dir(getCheckoutDir(platform)) {
        if(platform == 'Windows') {
            return getWindowsCommandOutput("git rev-parse HEAD")
        } else {
            return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        }
    }
}

def getWindowsCommandOutput(String script) {
    def out = bat(returnStdout: true, script: script).trim().split("\r?\n")
    if(out.size() == 2) {
        return out[1]
    }
    return ""
}

def doBuild(String platform) {
    dir(getCheckoutDir(platform)) {
        try {
            def force_build = to_fucking_bool(force_build)

            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            assert archiveDir : "Got an empty archive dir"
            assert !archiveDir.contains("D:") || platform == 'Windows' : "Got a Windows path on platform " + platform + " from getArchiveDirAndEnsureItExists() in doBuild()"
            assert !archiveDir.contains("/jenkins/") || platform != 'Windows' : "Got a Unix path on Windows from getArchiveDirAndEnsureItExists() in doBuild()"
            def toBuild = getExpectedProducts(platform)
            def archivedProductPaths = addPrefix(toBuild, archiveDir)
            if(!force_build && filesExist(archivedProductPaths, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
                // Copy them back to our working directories for the sake of archiving
                chooseShellByPlatformNixWin("cp ${archiveDir}* .", "copy \"${archiveDir}*\" .", platform)
                if(platform == 'macOS') {
                    sh "unzip -o '*.zip'" // single-quotes necessary so that the dumbass unzip command doesn't think we're specifying files within the first expanded arg
                }
            } else { // Actually build some stuff!
                def do_clean = to_fucking_bool(clean_build)
                def do_steam = to_fucking_bool(steam_build)
                def do_release = to_fucking_bool(release_build)
                if(platform == 'Windows') {
                    if(do_clean) {
                        bat "\"${tool 'MSBuild'}\" design_vstudio/design.sln /t:Clean"
                    }
                    def config = do_steam ? "Steam Prod Release" : (do_release ? "Prod Release" : "Release")
                    runMsBuild(config)
                } else if(platform == 'Linux') {
                    if(do_clean) {
                        sh 'make clean'
                    }
                    def cmd = (do_steam ? 'STEAM=1 ' : '') + 'REL=1 DEV=0 ARCHES="x86_64" make -j4 sim pln afl ins'
                    sh cmd
                } else {
                    if(do_clean) {
                        sh 'xcodebuild -project design_xcode4.xcodeproj clean'
                    }
                    def scheme = do_steam ? "Build Steam Release" : (do_release ? "Build Release" : "Build All NO-DEV-NO-OPT")
                    runXcodeBuild(scheme)
                }
            }
        } catch (e) {
            notifyDeadBuild(platform, e)
        }
    }
}

def filesExist(List expectedProducts, String platform) {
    for(def p : expectedProducts) {
        assert (!p.contains("D:") && !p.contains(".exe")) || platform == 'Windows' : "Got a Windows path on platform " + platform + " in filesExist()"
        assert (!p.contains("/jenkins/") && !p.contains(".app")) || platform != 'Windows' : "Got a Unix path on Windows in filesExist()"
        if(!fileExistsAbs(p, platform)) {
            //echo "Couldn't find file ${p} on ${platform}"
            return false
        }
    }
    return true
}

def fileExistsAbs(String file, String platform) { // Jenkins fileExists() doesn't seem to handle absolute paths... (ugh.)
    return fileExists(file)
    /*if(platform == 'Windows') {
        def output = getWindowsCommandOutput("IF EXIST \"${file}\" ( echo true )")
        return output.trim() == "true"
    } else {
        def f = escapeSlashes(file, platform)
        return sh(returnStatus: true, script: "test -e ${f}") == 0
    }*/
}

def runMsBuild(String configuration) {
    bat "\"${tool 'MSBuild'}\" design_vstudio/design.sln /t:Build /m /p:Configuration=\"${configuration}\" /p:Platform=\"x64\" /p:ProductVersion=11.${env.BUILD_NUMBER}"
}
def runXcodeBuild(String scheme) {
    sh "xcodebuild -scheme \"${scheme}\" -project design_xcode4.xcodeproj build"
}

def doTest(String platform) {
    def nix = platform != 'Windows'
    if(platform != 'Windows') {
        def checkoutDir = getCheckoutDir(platform)
        echo "Running tests"
        dir(checkoutDir + "tests") {
            def appNoExt = "X-Plane" + getAppSuffix(platform)
            def app = appNoExt + getAppPattern(platform).replace('*', '') + (platform == 'macOS' ? "/Contents/MacOS/${appNoExt}" : '')
            def binSubdir = nix ? "bin" : "Scripts"
            def venvPath = platform == 'macOS' ? '/usr/local/bin/' : ''
            sh "${venvPath}virtualenv env && env/${binSubdir}/pip install -r package_requirements.txt && env/${binSubdir}/python test_runner.py jenkins_smoke_test.test --app ../${app}"
        }
    }
}

def doArchive(String platform) {
    try {
        def checkout_dir = getCheckoutDir(platform)
        dir(checkout_dir) {
            def dropbox_path = getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkout_dir} to ${dropbox_path}"

            def file_pattern = getAppPattern(platform)
            def symbolsPattern = chooseByPlatformMacWinLin(["*.dSYM", "*.sym", "*.sym"], platform)
            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(platform == 'macOS') {
                sh "find . -name '${file_pattern}' -exec zip -r '{}'.zip '{}' \\;"
                sh "find . -name '${symbolsPattern}' -exec zip -r '{}'.zip '{}' \\;"
                file_pattern += '.zip'
                symbolsPattern += '.zip'
            }

            archiveArtifacts artifacts: file_pattern, fingerprint: true, onlyIfSuccessful: true
            def needs_symbols = to_fucking_bool(release_build) || to_fucking_bool(steam_build)
            def nix = platform != 'Windows'
            if(needs_symbols) {
                if(!nix) {
                    archiveArtifacts artifacts: "*.pdb", fingerprint: true, onlyIfSuccessful: true
                }
                archiveArtifacts artifacts: symbolsPattern, fingerprint: true, onlyIfSuccessful: true
            }

            def screenshots = []
            if(nix) {
                for(String screenshotName : getTestingScreenshotNames()) {
                    def newName = "${screenshotName}_${platform}.png"
                    moveFilePatternToDest("${screenshotName}_1.png", newName, platform)
                    screenshots.add(newName)
                }
                archiveArtifacts artifacts: screenshots.join(", "), fingerprint: true, onlyIfSuccessful: false
            }

            def dest = nix ? escapeSlashes(dropbox_path, platform) : dropbox_path
            moveFilePatternToDest(file_pattern, dest, platform)
            if(needs_symbols) {
                moveFilePatternToDest(symbolsPattern, dest, platform)
                if(!nix) {
                    moveFilePatternToDest("*.pdb", dest, platform)
                }
            }
            for(String screenshot : screenshots) {
                moveFilePatternToDest(screenshot, dest, platform)
            }
        }
    } catch (e) {
        notifyFailedArchive(platform, e)
    }
}

def moveFilePatternToDest(String filePattern, String dest, String platform) {
    chooseShellByPlatformNixWin("mv $filePattern ${dest}",  "move /Y \"${filePattern}\" \"${dest}\"", platform)
}

def chooseShellByPlatformNixWin(nixCommand, winCommand, platform) {
    if(platform == 'Windows') {
        bat winCommand
    } else {
        sh nixCommand
    }
}

def getAppPattern(String platform) {
    return chooseByPlatformMacWinLin(["*.app", "*.exe", "*-x86_64"], platform)
}

def getExpectedProducts(String platform) {
    def app_ext = chooseByPlatformMacWinLin([".app.zip", ".exe", "-x86_64"], platform)
    def installerAppName = chooseByPlatformMacWinLin(["X-Plane 11 Installer", "X-Plane 11 Installer", "Installer"], platform)
    def app_names = addSuffix(["X-Plane", installerAppName, "Airfoil Maker", "Plane Maker"], getAppSuffix(platform))
    def platform_apps = addSuffix(app_names, app_ext)

    if(isRelease()) {
        // TODO: Does only X-Plane produce a .sym?
        def platform_other = addSuffix(app_names, ".sym")
        if(platform == 'Windows') {
            platform_other += addSuffix(app_names, ".pdb")
        }
        return platform_apps + platform_other
    }
    if(platform != 'Windows') {
        autoTestScreenshots = addSuffix(addSuffix(getTestingScreenshotNames(), "_" + platform), ".png")
    }
    return platform_apps
}

def getAppSuffix(String platform) {
    return isRelease() ? "" : (chooseByPlatformMacWinLin(["_NODEV_NOOPT", "_NODEV_OPT", ""], platform))
}

def getTestingScreenshotNames() {
    return ["sunset_scattered_clouds", "evening", "stormy"]
}

def isRelease() {
    return to_fucking_bool(steam_build) || to_fucking_bool(release_build)
}

def chooseByPlatformMacWinLin(macWinLinOptions, String platform) {
    assert macWinLinOptions.size() == 3 : "Got the wrong number of options to choose by platform"
    if(platform == 'macOS') {
        return macWinLinOptions[0]
    } else if(platform == 'Windows') {
        return macWinLinOptions[1]
    } else {
        assert platform == 'Linux' : "Got unknown platform ${platform} in chooseByPlatformMacWinLin()"
        return macWinLinOptions[2]
    }
}

def getArchiveDirAndEnsureItExists(String platform) {
    def commit_id = getCommitId(platform)
    if(platform == 'Windows') {
        def out = "D:\\Docs\\Dropbox\\jenkins-archive\\${commit_id}\\"
        try {
            bat "mkdir \"${out}\""
        } catch(e) { } // ignore errors if it already exists
        return out
    } else {
        def out = "/jenkins/Dropbox/jenkins-archive/${commit_id}/"
        try {
            def o = escapeSlashes(out, platform)
            sh "mkdir ${o}"
        } catch(e) { } // ignore errors if it already exists
        return out
    }
}

def escapeSlashes(String path, String platform) {
    assert platform != 'Windows' : "You tried to escape slashes on Windows"
    if(platform == 'Windows') {
        return path
    } else {
        assert !path.contains("\\ ")
        return path.replace(" ", "\\ ")
    }
}

def notifyDeadBuild(String platform, Exception e) {
    currentBuild.result = "FAILED"
    if(pmt_subject) {
        replyToTrigger("The automated build of commit ${pmt_subject} failed on ${platform}.", e.toString())
    } else {
        def b = getBranchName()
        def commit_id = getCommitId(platform)
        notifyBuild(platform + " build is broken [" + b + "; " + commit_id + "]",
                platform + " build of X-Plane Desktop commit " + commit_id + " from the branch " + b + " failed. There was a problem with one or more of X-Plane, Plane Maker, Airfoil Maker, or the installer.",
                e.toString())
    }
    throw e
}

def notifyFailedArchive(String platform, Exception e) {
    def b = getBranchName()
    notifyBuild('Jenkins archive step broken on ' + platform + ' [' + b + ']',
            'Archive step failed on ' + platform + ', branch ' + b + '. We will be unable to archive builds until this is fixed.',
            e.toString(),
            'tyler@x-plane.com')
    throw e
}

def replyToTrigger(String msg, String errorMsg='') {
    notifyBuild("Re: " + pmt_subject, msg, errorMsg, pmt_from)
}

def notifyBuild(String subj, String msg, String errorMsg, String recipient="") { // empty recipient means we'll send to the most likely suspects
    def summary = errorMsg.isEmpty() ?
            "Download the build products: ${BUILD_URL}artifact/*zip*/archive.zip" :
            "The error was: ${errorMsg}"

    def body = """${msg}
    
${summary}
        
Build URL: ${BUILD_URL}

Console Log (split by machine/task/subtask): ${BUILD_URL}flowGraphTable/

Console Log (plain text): ${BUILD_URL}console
"""
    emailext attachLog: true,
            body: body,
            subject: subj,
            to: recipient ? recipient : emailextrecipients([
                    [$class: 'CulpritsRecipientProvider'],
                    [$class: 'RequesterRecipientProvider']
            ])
}

def ping(String platform) {
    echo "${platform} online"
}

// Fuck Jenkins.
// It passes us our BOOLEAN parameters as goddamn strings. "false" and "true".
// So, if you try to, oh I don't know, USE THEM LIKE YOU WOULD A BOOLEAN,
// the string "false" evaluates to TRUE!!!!!
// "But Tyler," you say, "why don't you just do foo = to_fucking_bool(foo) at the top of the script and be done with it?"
// Great question.
// Because you also CAN'T CHANGE A VARIABLE'S TYPE AFTER IT'S BEEN CREATED.
def to_fucking_bool(String fakeBool) {
    return fakeBool == 'true'
}

def addPrefix(List strings, String newPrefix) {
    // Someday, when Jenkins supports the .collect function... (per JENKINS-26481)
    // return strings.collect({ s + newPrefix })
    def out = []
    for(def s : strings) {
        out += newPrefix + s
    }
    return out
}
def addSuffix(List strings, String newSuffix) {
    // Someday, when Jenkins supports the .collect function... (per JENKINS-26481)
    // return strings.collect({ newSuffix + s })
    def out = []
    for(def s : strings) {
        out += s + newSuffix
    }
    return out
}