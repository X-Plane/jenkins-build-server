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
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout) }
    stage('Build')                         { runOn3Platforms(this.&doBuild) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
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

def doCheckout(String platform) {
    // Nuke previous products
    dir(utils.getCheckoutDir()) {
        utils.nukeExpectedProductsIfExist(platform)
        if(utils.toRealBool(clean_build)) {
            try {
                utils.chooseShellByPlatformMacWinLin(['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'], platform)
            } catch (e) { }
        }
    }

    try {
        xplaneCheckout(branch_name, utils.getCheckoutDir(), false, platform)
    } catch(e) {
        currentBuild.result = "FAILED"
        notifyBuild("Jenkins Git checkout is broken on ${platform} [${branch_name}]",
                "${platform} Git checkout failed on branch ${branch_name}. We will be unable to continue until this is fixed.",
                e.toString(),
                'tyler@x-plane.com')
        throw e
    }
}

def doBuild(String platform) {
    dir(utils.getCheckoutDir()) {
        try {
            def archiveDir = getArchiveDirAndEnsureItExists()
            assert archiveDir : "Got an empty archive dir"
            assert !archiveDir.contains("C:") || utils.isWindows(platform) : "Got a Windows path on platform " + platform + " from utils.getArchiveDirAndEnsureItExists() in doBuild()"
            assert !archiveDir.contains("/jenkins/") || utils.isNix(platform) : "Got a Unix path on Windows from utils.getArchiveDirAndEnsureItExists() in doBuild()"
            def toBuild = utils.getExpectedProducts(platform)
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
            notifyDeadBuild(platform, e)
        }
    }
}


def getBuildToolConfiguration() {
    return utils.steam_build ? "NODEV_OPT_Prod_Steam" : (utils.release_build ? "NODEV_OPT_Prod" : "NODEV_OPT")
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

            def products = utils.getExpectedProducts(platform)
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
    } catch (e) {
        notifyFailedArchive(platform, e)
    }
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

def notifyFailedArchive(String platform, Exception e) {
    notifyBuild("Jenkins archive step failed on ${platform} [${branch_name}]",
            "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing build products.",
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
