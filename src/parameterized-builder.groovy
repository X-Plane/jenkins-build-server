// Check configuration preconditions
assert toRealBool(build_all_apps) || (!toRealBool(release_build) && !toRealBool(steam_build)), "Release & Steam builds require all apps to be built"

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
    if(pmt_subject && pmt_from) {
        stage('Respond')                   { replyToTrigger('Build started.\n\nThe automated build of commit ' + pmt_subject + ' is in progress.') }
    }
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
            'Windows' : { node('windows') { if(toRealBool(build_windows)) { closure('Windows') } } },
            'macOS'   : { node('mac')     { if(toRealBool(build_mac))     { closure('macOS')   } } },
            'Linux'   : { node('linux')   { if(toRealBool(build_linux))   { closure('Linux')   } } }
    )
}

def nukePreviousBuildProducts(String platform) {
    dir(getCheckoutDir(platform)) {
        try {
            def filePattern = getAppPattern(platform)
            chooseShellByPlatformNixWin("rm -Rf ${filePattern}", "del \"${filePattern}\"", platform)
        } catch(e) { } // No old build products lying around? No problem!
        try {
            // Also attempt to nuke any old X-Plane screenshots lying around
            chooseShellByPlatformNixWin("rm -f *.png", "del \"*.png\"", platform)
        } catch(e) { } // No old screenshots lying around? No problem!
    }
}

def getBranchName() {
    if(pmt_subject) { // this is an email-triggered build, so the branch/tag/commit to build is in the email's subject line
        return pmt_subject.trim()
    } else {
        return branch_name // this build was triggered by monitoring a particular Git branch, so the branch to build is set at the job level
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

            def commitId = getCommitId(platform)
            echo "Checked out commit ${commitId} on ${platform}"

            if(supportsTesting(platform)) {
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

def supportsTesting(platform) {
    return false && isNix(platform)
}

def getCheckoutDir(String platform) {
    return chooseByPlatformNixWin("/jenkins/design-${directory_suffix}/", "D:\\jenkins\\design-${directory_suffix}\\", platform)
}

def getCommitId(String platform) {
    dir(getCheckoutDir(platform)) {
        if(isWindows(platform)) {
            def out = bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")
            if(out.size() == 2) {
                return out[1]
            }
            return ""
        } else {
            return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        }
    }
}

def doBuild(String platform) {
    dir(getCheckoutDir(platform)) {
        try {
            def forceBuild = toRealBool(force_build)

            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            assert archiveDir : "Got an empty archive dir"
            assert !archiveDir.contains("D:") || isWindows(platform) : "Got a Windows path on platform " + platform + " from getArchiveDirAndEnsureItExists() in doBuild()"
            assert !archiveDir.contains("/jenkins/") || isNix(platform) : "Got a Unix path on Windows from getArchiveDirAndEnsureItExists() in doBuild()"
            def toBuild = getExpectedProducts(platform)
            def archivedProductPaths = addPrefix(toBuild, archiveDir)
            if(!forceBuild && filesExist(archivedProductPaths, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
                // Copy them back to our working directories for the sake of archiving
                chooseShellByPlatformNixWin("cp ${archiveDir}* .", "copy \"${archiveDir}*\" .", platform)
                if(isMac(platform)) {
                    sh "unzip -o '*.zip'" // single-quotes necessary so that the silly unzip command doesn't think we're specifying files within the first expanded arg
                }
            } else { // Actually build some stuff!
                def config = getBuildToolConfiguration(platform)

                if(toRealBool(clean_build)) {
                    chooseShellByPlatformMacWinLin([
                            "xcodebuild -project design_xcode4.xcodeproj clean && xcodebuild -scheme \"${config}\" -project design_xcode4.xcodeproj clean && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                            "\"${tool 'MSBuild'}\" design_vstudio/design.sln /t:Clean",
                            'make clean'
                    ], platform)
                }

                def doAll = toRealBool(build_all_apps)
                chooseShellByPlatformMacWinLin([
                        "xcodebuild -scheme \"${config}\" -project design_xcode4.xcodeproj build",
                        "\"${tool 'MSBuild'}\" /t:Build /m /p:Configuration=\"${config}\" /p:Platform=\"x64\" /p:ProductVersion=11.${env.BUILD_NUMBER} design_vstudio/" + (doAll ? "design.sln" : "X-Plane.vcxproj"),
                         "${config} make -j4 sim " + (doAll ? "pln afl ins" : "")
                ], platform)

            }
        } catch (e) {
            notifyDeadBuild(platform, e)
        }
    }
}

def filesExist(List expectedProducts, String platform) {
    for(def p : expectedProducts) {
        assert (!p.contains("D:") && !p.contains(".exe")) || isWindows(platform) : "Got a Windows path on platform " + platform + " in filesExist()"
        assert (!p.contains("/jenkins/") && !p.contains(".app")) || isNix(platform) : "Got a Unix path on Windows in filesExist()"
        if(!fileExists(p)) {
            return false
        }
    }
    return true
}

def getBuildToolConfiguration(String platform) {
    def doSteam = toRealBool(steam_build)
    def doRelease = toRealBool(release_build)
    def doAll = toRealBool(build_all_apps)
    if(doAll) {
        return chooseByPlatformMacWinLin([
                doSteam ? "Build Steam Release" : (doRelease ? "Build Release" : "Build All NO-DEV-NO-OPT"),
                doSteam ? "Steam Prod Release"  : (doRelease ? "Prod Release"  : "Release"),
                (doSteam ? 'STEAM=1 ' : '') + 'REL=1 DEV=0 ARCHES="x86_64"'], platform)
    } else {
        return chooseByPlatformMacWinLin([
                "X-Plane",
                "Release",
                (doSteam ? 'STEAM=1 ' : '') + 'REL=1 DEV=0 ARCHES="x86_64"'], platform)
    }
}

def doTest(String platform) {
    if(supportsTesting(platform)) {
        def checkoutDir = getCheckoutDir(platform)
        echo "Running tests"
        dir(checkoutDir + "tests") {
            def appNoExt = "X-Plane" + getAppSuffix(platform)
            def app = appNoExt + getAppPattern(platform).replace('*', '') + (isMac(platform) ? "/Contents/MacOS/${appNoExt}" : '')
            def binSubdir = chooseByPlatformNixWin("bin", "Scripts", platform)
            def venvPath = isMac(platform) ? '/usr/local/bin/' : ''
            sh "${venvPath}virtualenv env && env/${binSubdir}/pip install -r package_requirements.txt && env/${binSubdir}/python test_runner.py jenkins_smoke_test.test --app ../${app}"
        }
    }
}

def doArchive(String platform) {
    try {
        def checkoutDir = getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            def filePattern = getAppPattern(platform)
            def symbolsPattern = chooseByPlatformMacWinLin(["*.dSYM", "*.sym", "*.sym"], platform)
            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(isMac(platform)) {
                sh "find . -name '${filePattern}' -exec zip -r '{}'.zip '{}' \\;"
                sh "find . -name '${symbolsPattern}' -exec zip -r '{}'.zip '{}' \\;"
                filePattern += '.zip'
                symbolsPattern += '.zip'
            }

            archiveArtifacts artifacts: filePattern, fingerprint: true, onlyIfSuccessful: true
            def needsSymbols = isRelease()
            if(needsSymbols) {
                if(isWindows(platform)) {
                    archiveArtifacts artifacts: "*.pdb", fingerprint: true, onlyIfSuccessful: true
                }
                archiveArtifacts artifacts: symbolsPattern, fingerprint: true, onlyIfSuccessful: true
            }

            def screenshots = []
            if(supportsTesting(platform)) {
                for(String screenshotName : getTestingScreenshotNames()) {
                    def newName = "${screenshotName}_${platform}.png"
                    moveFilePatternToDest("${screenshotName}_1.png", newName, platform)
                    screenshots.add(newName)
                }
                archiveArtifacts artifacts: screenshots.join(", "), fingerprint: true, onlyIfSuccessful: false
            }

            def dest = escapeSlashes(dropboxPath, platform)
            moveFilePatternToDest(filePattern, dest, platform)
            if(needsSymbols) {
                moveFilePatternToDest(symbolsPattern, dest, platform)
                if(isWindows(platform)) {
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
    chooseShellByPlatformMacWinLin([nixCommand, winCommand, nixCommand], platform)
}
def chooseShellByPlatformMacWinLin(List macWinLinCommands, platform) {
    if(isWindows(platform)) {
        bat macWinLinCommands[1]
    } else if(isMac(platform)) {
        sh macWinLinCommands[0]
    } else {
        sh macWinLinCommands[2]
    }
}

def getAppPattern(String platform) {
    return chooseByPlatformMacWinLin(["*.app", "*.exe", "*-x86_64"], platform)
}

def getExpectedProducts(String platform) {
    def appExt = chooseByPlatformMacWinLin([".app.zip", ".exe", "-x86_64"], platform)
    def installerAppName = chooseByPlatformMacWinLin(["X-Plane 11 Installer", "X-Plane 11 Installer", "Installer"], platform)
    def xplaneApp = addSuffix(["X-Plane"], getAppSuffix(platform))
    def otherApps = addSuffix([installerAppName, "Airfoil Maker", "Plane Maker"], getAppSuffix(platform))
    def doAll = toRealBool(build_all_apps)
    def appNames = doAll ? xplaneApp + otherApps : xplaneApp
    def platformApps = addSuffix(appNames, appExt)

    if(isRelease()) {
        // TODO: Does only X-Plane produce a .sym?
        def platformOther = addSuffix(appNames, ".sym")
        if(isWindows(platform)) {
            platformOther += addSuffix(appNames, ".pdb")
        }
        return platformApps + platformOther
    }
    if(supportsTesting(platform)) {
        autoTestScreenshots = addSuffix(addSuffix(getTestingScreenshotNames(), "_" + platform), ".png")
    }
    return platformApps
}

def getAppSuffix(String platform) {
    return isRelease() ? "" : (chooseByPlatformMacWinLin(["_NODEV_NOOPT", "_NODEV_OPT", ""], platform))
}

def getTestingScreenshotNames() {
    return ["sunset_scattered_clouds", "evening", "stormy"]
}

def isRelease() {
    return toRealBool(steam_build) || toRealBool(release_build)
}

def chooseByPlatformMacWinLin(macWinLinOptions, String platform) {
    assert macWinLinOptions.size() == 3 : "Got the wrong number of options to choose by platform"
    if(isMac(platform)) {
        return macWinLinOptions[0]
    } else if(isWindows(platform)) {
        return macWinLinOptions[1]
    } else {
        assert isNix(platform) : "Got unknown platform ${platform} in chooseByPlatformMacWinLin()"
        return macWinLinOptions[2]
    }
}

def chooseByPlatformNixWin(nixVersion, winVersion, String platform) {
    return chooseByPlatformMacWinLin([nixVersion, winVersion, nixVersion], platform)
}

def getArchiveDirAndEnsureItExists(String platform) {
    def commitId = getCommitId(platform)
    def out = escapeSlashes(chooseByPlatformNixWin("/jenkins/Dropbox/jenkins-archive/${commitId}/", "D:\\Docs\\Dropbox\\jenkins-archive\\${commitId}\\", platform), platform)
    try {
        chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"", platform)
    } catch(e) { } // ignore errors if it already exists
    return out
}

def escapeSlashes(String path, String platform) {
    if(isWindows(platform)) {
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
        def commitId = getCommitId(platform)
        notifyBuild(platform + " build is broken [" + b + "; " + commitId + "]",
                platform + " build of X-Plane Desktop commit " + commitId + " from the branch " + b + " failed. There was a problem with one or more of X-Plane, Plane Maker, Airfoil Maker, or the installer.",
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

// $&@#* Jenkins.
// It passes us our BOOLEAN parameters as freaking strings. "false" and "true".
// So, if you try to, oh I don't know, USE THEM LIKE YOU WOULD A BOOLEAN,
// the string "false" evaluates to TRUE!!!!!
// "But Tyler," you say, "why don't you just do foo = toRealBool(foo) at the top of the script and be done with it?"
// Great question.
// Because you also CAN'T CHANGE A VARIABLE'S TYPE AFTER IT'S BEEN CREATED.
def toRealBool(String fakeBool) {
    return fakeBool == 'true'
}

def isWindows(String platform) {
    return platform == 'Windows'
}
def isNix(String platform) {
    return !isWindows(platform)
}
def isMac(String platform) {
    return platform == 'macOS'
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