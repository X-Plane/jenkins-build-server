// If this is an email-triggered build, the branch/tag/commit to build is in the email's subject line
def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = ''
environment['directory_suffix'] = ''
environment['pmt_from'] = ''
environment['release_build'] = false
environment['build_windows'] = build_windows
environment['build_mac'] = build_mac
environment['build_linux'] = build_linux
environment['dev_build'] = false
utils.setEnvironment(environment, this.&notify, this.steps)

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
    stage('Respond')                       { utils.replyToTrigger("Build started.\n\nThe automated build of commit ${branch_name} is in progress.") }
    stage('Checkout')                      { runOn3Platforms(this.&doCheckout) }
    stage('Build')                         { runOn3Platforms(this.&doBuild) }
    stage('Archive')                       { runOn3Platforms(this.&doArchive) }
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
            'Windows' : { if(utils.build_windows) { node('windows') { timeout(60 * 2) { closure('Windows') } } } },
            'macOS'   : { if(utils.build_mac)     { node('mac')     { timeout(60 * 2) { closure('macOS')   } } } },
            'Linux'   : { if(utils.build_linux)   { node('linux')   { timeout(60 * 2) { closure('Linux')   } } } }
    )
}

def doClean(List<String> productsToClean, List<String> macWinLinCleanCommmand, String platform) {
    if(productsToClean || macWinLinCleanCommmand) {
        dir(getCheckoutDir(platform)) {
            utils.nukeIfExist(productsToClean, platform)
            if(macWinLinCleanCommmand) {
                try {
                    utils.chooseShellByPlatformMacWinLin(macWinLinCleanCommmand, platform)
                } catch (e) { }
            }
        }
    }
}

def doCheckout(String platform) {

    if(utils.toRealBool(clean_build)) {
        // Nuke previous products
        cleanCommand = utils.toRealBool(clean_build) ? ['rm -Rf cmake_xcode', 'rd /s /q cmake_vstudio', 'rm -Rf cmake_linux'] : []
        doClean(getExpectedProducts(platform), cleanCommand, platform)
    }

    try {
        xplaneCheckout(branch_name, getCheckoutDir(platform), platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/gfx-cc.git')
        dir(getCheckoutDir(platform)) {
            utils.chooseShell('git submodule update --init', platform)
        }
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'gfx-cc', branch_name, platform, e)
    }
}

def doBuild(String platform) {
    dir(getCheckoutDir(platform)) {
        try {
            def archiveDir = getArchiveDirAndEnsureItExists(platform)
            def toBuild = getExpectedProducts(platform)
            echo 'Expecting to build: ' + toBuild.join(', ')
            if(!utils.toRealBool(force_build) && utils.copyBuildProductsFromArchive(toBuild, platform)) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else { // Actually build some stuff!
                def config = getBuildToolConfiguration()

                // Generate our project files
                utils.chooseShellByPlatformMacWinLin([
                        'cd SDK/glslang && python ./update_glslang_sources.py && cd ../.. && ./cmake.sh',
                        'cmd /C ""%VS140COMNTOOLS%vsvars32.bat" && cd SDK/glslang && python ./update_glslang_sources.py && cd ../.. && cmake.bat"',
                        "cd SDK/glslang && python ./update_glslang_sources.py && cd ../.. && ./cmake.sh ${config}"
                ], platform)

                def projectFile = utils.chooseByPlatformNixWin("cmake_xcode/X-GFX-CC.xcodeproj", "cmake_vstudio\\X-GFX-CC.sln", platform)

                def pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''

                String target = "gfx-cc"
                if(utils.toRealBool(clean_build)) {
                    utils.chooseShellByPlatformMacWinLin([
                            "set -o pipefail && xcodebuild -project ${projectFile} clean ${pipe_to_xcpretty} && xcodebuild -target \"${target}\" -config \"${config}\" -project ${projectFile} clean ${pipe_to_xcpretty} && rm -Rf /Users/tyler/Library/Developer/Xcode/DerivedData/*",
                            "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                            'cd cmake_linux && make clean'
                    ], platform)
                }

                utils.chooseShellByPlatformMacWinLin([
                        "set -o pipefail && xcodebuild -target \"${target}\" -config \"${config}\" -project ${projectFile} build ${pipe_to_xcpretty}",
                        "\"${tool 'MSBuild'}\" /t:Build /m /p:Configuration=\"${config}\" /p:Platform=\"x64\" /p:ProductVersion=11.${env.BUILD_NUMBER} cmake_vstudio\\X-GFX-CC.sln",
                        "cd cmake_linux && make -j\$(nproc) " + "gfx-cc"
                ], platform)
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'GFX-CC', branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def doArchive(String platform) {
    try {
        def checkoutDir = getCheckoutDir(platform)
        dir(checkoutDir) {
            def dropboxPath = getArchiveDirAndEnsureItExists(platform)
            echo "Copying files from ${checkoutDir} to ${dropboxPath}"

            productPaths = utils.addPrefix(getExpectedProducts(platform), utils.chooseByPlatformMacWinLin([
                    'cmake_xcode/gfx-cc/' + getBuildToolConfiguration() + '/',
                    'cmake_vstudio\\gfx-cc\\' + getBuildToolConfiguration() + '\\',
                    'cmake_linux/gfx-cc/',
            ], platform))

            archiveWithDropbox(productPaths, dropboxPath, true, utils)
        }
    } catch (e) {
        utils.sendEmail("Jenkins archive step failed on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing build products.",
                e.toString())
        throw e
    }
}

String getBuildToolConfiguration() {
    return "Release"
}
List<String> getExpectedProducts(String platform) {
    return utils.chooseByPlatformMacWinLin([
            ['gfx-cc'],
            ['gfx-cc.exe', 'gfx-cc.pdb'],
            ['gfx-cc-lin']
    ], platform)
}
String getCheckoutDir(String platform) {
    return utils.chooseByPlatformNixWin("/jenkins/gfx-cc/", "C:\\jenkins\\gfx-cc\\", platform)
}

String getArchiveDirAndEnsureItExists(String platform) {
    def out = utils.getArchiveDir(platform, 'gfx-cc')
    try {
        utils.chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"")
    } catch(e) { } // ignore errors if it already exists
    return out
}