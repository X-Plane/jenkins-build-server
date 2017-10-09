library 'build-utils'

// If this is an email-triggered build, the branch/tag/commit to build is in the email's subject line
branch_name = pmt_subject ? pmt_subject.trim() : branch_name

def environment = [:]
environment['branch_name'] = branch_name
environment['directory_suffix'] = directory_suffix
environment['release_build'] = release_build
environment['steam_build'] = steam_build
environment['build_windows'] = build_windows
environment['build_mac'] = build_mac
environment['build_linux'] = build_linux
environment['build_all_apps'] = build_all_apps
utils.setEnvironment(environment)

// Check configuration preconditions
assert utils.build_all_apps || (!utils.release_build && !utils.steam_build), "Release & Steam builds require all apps to be built"

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
    try {
        stage('Test')                      { runOn3Platforms(this.&doTest) }
    } finally { // we want to archive regardless of whether the tests passed
        stage('Archive')                   { runOn3Platforms(this.&doArchive) }
    }
    if(pmt_subject && pmt_from) {
        stage('Notify')                    { replyToTrigger('SUCCESS!\n\nThe automated build of commit ' + pmt_subject + ' succeeded.') }
    }
} finally {
    node('windows') { step([$class: 'LogParserPublisher', failBuildOnError: false, parsingRulesPath: 'C:/jenkins/log-parser-builds.txt', useProjectRule: false]) }
}

def runOn3Platforms(Closure c) {
    def closure = c
    parallel (
            'Windows' : { node('windows') { if(utils.build_windows) { closure('Windows') } } },
            'macOS'   : { node('mac')     { if(utils.build_mac)     { closure('macOS')   } } },
            'Linux'   : { node('linux')   { if(utils.build_linux)   { closure('Linux')   } } }
    )
}

def nukePreviousBuildProducts(String platform) {
    dir(utils.getCheckoutDir()) {
        for(def p : getExpectedBuildPlusTestProducts(platform)) {
            try {
                utils.chooseShellByPlatformNixWin("rm -Rf ${p}", "del \"${p}\"")
            } catch(e) { } // No old build products lying around? No problem!
        }
        if(utils.clean_build) {
            try {
                utils.chooseShellByPlatformMacWinLin(['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'], platform)
            } catch (e) { }
        }
        try {
            utils.chooseShellByPlatformNixWin('rm *.png', 'del "*.png"')
        } catch(e) { }
    }
}

def doCheckout(String platform) {
    xplaneCheckout(branch_name, utils.getCheckoutDir(), platform)
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir()) {
        try {
            def archiveDir = utils.getArchiveDirAndEnsureItExists()
            assert archiveDir : "Got an empty archive dir"
            assert !archiveDir.contains("C:") || utils.isWindows(platform) : "Got a Windows path on platform " + platform + " from utils.getArchiveDirAndEnsureItExists() in doBuild()"
            assert !archiveDir.contains("/jenkins/") || utils.isNix(platform) : "Got a Unix path on Windows from utils.getArchiveDirAndEnsureItExists() in doBuild()"
            def toBuild = getExpectedBuildPlusTestProducts(platform)
            echo 'Expecting to build: ' + toBuild.join(', ')
            if(!toRealBool(force_build) && utils.copyBuildProductsFromArchive(toBuild)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                def config = getBuildToolConfiguration(platform)

                // Generate our project files
                utils.chooseShellByPlatformMacWinLin(['./cmake.sh', 'cmd /C ""%VS140COMNTOOLS%vsvars32.bat" && cmake.bat"', "./cmake.sh ${config}"], platform)

                def projectFile = utils.chooseByPlatformNixWin("design_xcode/X-System.xcodeproj", "design_vstudio\\X-System.sln")

                def target = utils.build_all_apps ? "ALL_BUILD" : "X-Plane"
                if(utils.clean_build) {
                    utils.chooseShellByPlatformMacWinLin([
                            "set -o pipefail && xcodebuild -project ${projectFile} clean | xcpretty && xcodebuild -scheme \"${target}\" -config \"${config}\" -project ${projectFile} clean | xcpretty && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                            "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                            'cd design_linux && make clean'
                    ], platform)
                }

                utils.chooseShellByPlatformMacWinLin([
                        "set -o pipefail && xcodebuild -scheme \"${target}\" -config \"${config}\" -project ${projectFile} build | xcpretty",
                        "\"${tool 'MSBuild'}\" /t:Build /m /p:Configuration=\"${config}\" /p:Platform=\"x64\" /p:ProductVersion=11.${env.BUILD_NUMBER} design_vstudio\\" + (utils.build_all_apps ? "X-System.sln" : "source_code\\app\\X-Plane-f\\X-Plane.vcxproj"),
                        "cd design_linux && make -j4 " + (utils.build_all_apps ? '' : "X-Plane")
                ], platform)

            }
        } catch (e) {
            notifyDeadBuild(platform, e)
        }
    }
}


def getBuildToolConfiguration(String platform) {
    return utils.steam_build ? "NODEV_OPT_Prod_Steam" : (utils.release_build ? "NODEV_OPT_Prod" : "NODEV_OPT")
}

def doTest(String platform) {
    if(utils.supportsTesting(platform)) {
        def checkoutDir = utils.getCheckoutDir()
        echo "Running tests"
        dir(checkoutDir + "tests") {
            def app = "X-Plane" + utils.app_suffix + utils.chooseByPlatformMacWinLin([".app/Contents/MacOS/X-Plane" + utils.app_suffix, ".exe", '-x86_64'], platform)
            def binSubdir = utils.chooseByPlatformNixWin("bin", "Scripts")
            def venvPath = utils.isMac(platform) ? '/usr/local/bin/' : ''
            def cmd = "${venvPath}virtualenv env && env/${binSubdir}/pip install -r package_requirements.txt && env/${binSubdir}/python test_runner.py jenkins_smoke_test.test --nodelete --app ../${app}"
            echo cmd
            try {
                sh cmd
            } catch(e) {
                echo "Test failed on platform ${platform}... archiving Log.txt"
                archiveArtifacts artifacts: "Log.txt", fingerprint: true, onlyIfSuccessful: false
                notifyTestFailed(platform, e)
                throw e
            }
        }
    }
}

def doArchive(String platform) {
    try {
        def checkoutDir = utils.getCheckoutDir()
        dir(checkoutDir) {
            def dropboxPath = getArchiveDirAndEnsureItExists()
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "find . -name '*.app' -exec zip -r '{}'.zip '{}' \\;"
                sh "find . -name '*.dSYM' -exec zip -r '{}'.zip '{}' \\;"
            }

            def products = getExpectedBuildPlusTestProducts(platform)

            try {
                if(utils.supportsTesting(platform)) {
                    for(String screenshotName : getTestingScreenshotNames()) {
                        utils.moveFilePatternToDest("${screenshotName}_1.png", "${screenshotName}_${platform}.png")
                    }
                }
            } finally {
                archiveArtifacts artifacts: products.join(', '), fingerprint: true, onlyIfSuccessful: false

                def dest = utils.escapeSlashes(dropboxPath)
                for(String p : products) {
                    // Do *NOT* copy to Dropbox if the products already exist! We need to treat the Dropbox archives as write-once
                    if(fileExists(dest + p)) {
                        echo "Skipping copy of ${p} to Dropbox, since the file already exists in ${dest}"
                    } else {
                        utils.moveFilePatternToDest(p, dest)
                    }
                }
            }
        }
    } catch (e) {
        notifyFailedArchive(platform, e)
    }
}

List getExpectedBuildPlusTestProducts(String platform) {
    List executables = utils.getExpectedProducts(platform)
    if(utils.supportsTesting(utils.steam_build)) {
        // Screenshots from tests
        return executables + utils.addSuffix(utils.addSuffix(getTestingScreenshotNames(), "_" + platform), ".png")
    }
    return executables
}

def getTestingScreenshotNames() {
    return ["sunset_scattered_clouds", "evening", "stormy"]
}

def getArchiveDirAndEnsureItExists() {
    def out = utils.getArchiveDir()
    try {
        utils.chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"")
    } catch(e) { } // ignore errors if it already exists
    return out
}

def notifyDeadBuild(String platform, Exception e) {
    currentBuild.result = "FAILED"
    if(pmt_subject) {
        replyToTrigger("The automated build of commit ${pmt_subject} failed on ${platform}.", e.toString())
    } else {
        def commitId = utils.getCommitId()
        notifyBuild(platform + " build is broken [" + branch_name + "; " + commitId + "]",
                platform + " build of X-Plane Desktop commit " + commitId + " from the branch " + branch_name + " failed. There was a problem with one or more of X-Plane, Plane Maker, Airfoil Maker, or the installer.",
                e.toString())
    }
    throw e
}

def notifyTestFailed(String platform, Exception e) {
    currentBuild.result = "FAILED"
    if(pmt_subject) {
        replyToTrigger("Automated testing of commit ${pmt_subject} failed on ${platform}.", e.toString())
    } else {
        def commitId = utils.getCommitId()
        notifyBuild("Testing failed on ${platform} [${branch_name}; ${commitId}]",
                "Build X-Plane Desktop commit ${commitId} from the branch ${branch_name} succeeded on ${platform}, but the auto-testing failed.",
                e.toString())
    }
    throw e
}

def notifyFailedArchive(String platform, Exception e) {
    notifyBuild("Jenkins archive step failed on ${platform} [${branch_name}]",
            "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing screenshot(s) from automated tests. (Possibly due to a crash?)",
            e.toString())
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
    if(utils.toRealBool(send_emails)) {
        emailext attachLog: true,
                body: body,
                subject: subj,
                to: recipient ? recipient : emailextrecipients([
                        [$class: 'CulpritsRecipientProvider'],
                        [$class: 'RequesterRecipientProvider']
                ])
    }
}
