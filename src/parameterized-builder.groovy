// If this is an email-triggered build, the branch/tag/commit to build is in the email's subject line
branch_name = pmt_subject ? pmt_subject.trim() : branch_name

def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = pmt_subject
environment['pmt_from'] = pmt_from
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
    stage('Respond')                       { utils.replyToTriggerF("Build started.\n\nThe automated build of commit ${branch_name} is in progress.") }
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout) }
    stage('Build')                         { runOn3Platforms(this.&doBuild) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
    stage('Notify')                        { utils.replyToTriggerF("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded.") }
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

def doCheckout(String platform) {
    // Nuke previous products
    cleanCommand = utils.toRealBool(clean_build) ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(utils.getExpectedXPlaneProducts(platform), cleanCommand, platform, utils)

    try {
        xplaneCheckout(branch_name, utils.getCheckoutDir(platform), platform)
    } catch(e) {
        notifyBrokenCheckout(utils.sendEmailF, 'X-Plane', branch_name, platform, e)
    }
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir(platform)) {
        try {
            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            assert archiveDir : "Got an empty archive dir"
            assert !archiveDir.contains("C:") || utils.isWindows(platform) : "Got a Windows path on platform " + platform + " from utils.getArchiveDirAndEnsureItExists() in doBuild()"
            assert !archiveDir.contains("/jenkins/") || utils.isNix(platform) : "Got a Unix path on Windows from utils.getArchiveDirAndEnsureItExists() in doBuild()"
            def toBuild = utils.getExpectedXPlaneProducts(platform)
            echo 'Expecting to build: ' + toBuild.join(', ')
            if(!utils.toRealBool(force_build) && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                def config = getBuildToolConfiguration()

                // Generate our project files
                utils.chooseShellByPlatformMacWinLin(['./cmake.sh', 'cmd /C ""%VS140COMNTOOLS%vsvars32.bat" && cmake.bat"', "./cmake.sh ${config}"], platform)

                def projectFile = utils.chooseByPlatformNixWin("design_xcode/X-System.xcodeproj", "design_vstudio\\X-System.sln", platform)

                String target = utils.build_all_apps ? "ALL_BUILD" : "X-Plane"
                if(utils.toRealBool(clean_build)) {
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
            notifyDeadBuild(utils.sendEmailF, 'X-Plane', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}


def getBuildToolConfiguration() {
    return utils.steam_build ? "NODEV_OPT_Prod_Steam" : (utils.release_build ? "NODEV_OPT_Prod" : "NODEV_OPT")
}

def doArchive(String platform) {
    try {
        def checkoutDir = utils.getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            // If we're on macOS, the "executable" is actually a directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                sh "find . -name '*.app' -exec zip -r '{}'.zip '{}' \\;"
                sh "find . -name '*.dSYM' -exec zip -r '{}'.zip '{}' \\;"
            }

            archiveWithDropbox(utils.getExpectedXPlaneProducts(platform), dropboxPath, true, utils)
        }
    } catch (e) {
        utils.sendEmailF("Jenkins archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing build products.",
                e.toString())
        throw e
    }
}

String getArchiveDirAndEnsureItExists(String platform) {
    def out = utils.getArchiveDir(platform)
    try {
        utils.chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"")
    } catch(e) { } // ignore errors if it already exists
    return out
}
