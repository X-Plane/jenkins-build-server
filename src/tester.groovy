// If this is an email-triggered test, the branch/tag/commit to test is in the email's subject line
branch_name = pmt_subject ? pmt_subject.trim() : branch_name


def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = pmt_subject
environment['pmt_from'] = pmt_from
environment['directory_suffix'] = directory_suffix
environment['build_windows'] = 'true'
environment['build_mac'] = 'true'
environment['build_linux'] = 'true'
environment['build_all_apps'] = 'false'
environment['build_type'] = build_type
utils.setEnvironment(environment, this.&notify)

isFpsTest = test_type == 'fps_test'
isSmokeTest = test_type == 'smoke_test'
isRenderingRegressionMaster = test_type == 'rendering_regression_new_master'
isRenderingRegressionRelease = test_type == 'rendering_regression_new_release'
isRenderingRegressionComparison = test_type == 'rendering_regression_compare'
isRenderingRegression = isRenderingRegressionMaster || isRenderingRegressionRelease || isRenderingRegressionComparison
regressionMasterArchive = utils.getArchiveRoot(platform) + 'rendering-master/'
regressionReleaseArchive = utils.getArchiveRoot(platform) + 'rendering-release/'
isTimeTest = test_type == 'load_time'
String nodeType = platform.startsWith('Windows') ? 'windows' : (platform == 'Linux' ? 'linux' : 'mac')
node(nodeType) {
    checkoutDir = utils.getCheckoutDir(platform)
}
logFilesToArchive = []

//--------------------------------------------------------------------------------------------------------------------------------
// RUN THE TESTS
// This is where the magic happens.
//--------------------------------------------------------------------------------------------------------------------------------
stage('Respond')             { utils.replyToTrigger("Automated testing of commit ${branch_name} is in progress on ${platform}.") }
stage('Checkout')            { node(nodeType) { doCheckout() } }
try {
    stage('Test')            { node(nodeType) { timeout(60 * 12) { doTest() } } }
} finally { // we want to archive regardless of whether the tests passed
    stage('Archive')         { node(nodeType) { doArchive() } }
}
stage('Notify')              { utils.replyToTrigger("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded on ${platform}.") }


//--------------------------------------------------------------------------------------------------------------------------------
// IMPLEMENTATION
//--------------------------------------------------------------------------------------------------------------------------------
def doCheckout() {
    // Nuke previous products
    boolean doClean = utils.toRealBool(clean_build)
    cleanCommand = doClean ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(utils.getExpectedXPlaneProducts(platform, true) + ['*.png', 'regression_images', 'Resources/shaders/bin/'], cleanCommand, platform, utils)

    try {
        xplaneCheckout(branch_name, checkoutDir, platform)
        if(utils.toRealBool(do_svn_checkout)) {
            getArt(checkoutDir)
        }
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'autotesting', branch_name, platform, e)
    }

    String thirdPartySha = ''
    dir(checkoutDir) {
        String shaFilePath = 'tests/jenkins_third_party_checkout.sha'
        if(fileExists(shaFilePath)) { // also check out from the third party repor
            thirdPartySha = readFile(shaFilePath).trim()
        }
    }

    if(thirdPartySha) {
        String thirdPartyCheckoutDir = chooseByPlatformNixWin("/jenkins/third_party_testers/", "C:\\jenkins\\third_party_testers\\", platform)
        xplaneCheckout(thirdPartySha, thirdPartyCheckoutDir, platform, 'ssh://tyler@dev.x-plane.com/admin/git-xplane/third_party_testers.git')
    }

    // Copy pre-built executables to our working dir as well
    dir(checkoutDir) {
        def products = utils.getExpectedXPlaneProducts(platform, true)
        def archiveDir = getArchiveDir()

        // We're going to attempt to copy our dependencies once/minute for up to 10 minutes (to give Dropbox time to sync)
        boolean copied = false
        int secondsWaited = 0
        def timeout = 60 * (checkout_max_wait_minutes as Integer)
        while(!copied) {
            if(utils.copyBuildProductsFromArchive(products, platform)) {
                echo "Copied executables for ${platform} in ${archiveDir}"
                break
            } else if(secondsWaited < timeout) {
                // Tyler says: Jenkins overrides the Java-provided sleep with its own version.
                //             The Jenkins version "conveniently" takes different units than the Java version:
                //             it expects *seconds*, not milliseconds. (WTF?)
                sleep(30)
                secondsWaited += 30
            } else {
                def commitId = utils.getCommitId(platform)
                def prodStr = utils.addPrefix(products, archiveDir).join(', ')
                utils.sendEmail(
                        "Testing failed on ${platform} [${branch_name}; ${commitId}]",
                        "Missing executables to test on ${platform} [${branch_name}]",
                        "Couldn't find pre-built binaries to test for ${platform} on branch ${branch_name} after waiting ${checkout_max_wait_minutes} minutes.\r\n\r\nWe were looking for:\r\n${prodStr}\r\nin directory:\r\n${archiveDir}\r\n\r\nWe will be unable to test until this is fixed.",
                        'tyler@x-plane.com')
                echo 'Throwing error due to missing products: ' + prodStr
                throw new java.io.FileNotFoundException(prodStr)
            }
        }

        secondsWaited = 0
        while(secondsWaited <= (5 * 60) && !attemptCopyAndUnzipShaders(platform)) {
            // Tyler says: Jenkins overrides the Java-provided sleep with its own version.
            //             The Jenkins version "conveniently" takes different units than the Java version:
            //             it expects *seconds*, not milliseconds. (WTF?)
            sleep(30)
            echo 'Waiting for shaders'
            secondsWaited += 30
        }
    }
}

def attemptCopyAndUnzipShaders(String platform) {
    String shadersSuffix = utils.isWindows(platform) ? 'Windows' : platform
    // the "legacy" location, from back when we were compiling shaders individually on 3 platforms for no good reason
    String legacyZip = "shaders_bin_${shadersSuffix}.zip"

    for(String potentialZip : ['shaders_bin.zip', legacyZip]) {
        if(utils.copyBuildProductsFromArchive([potentialZip], platform)) {
            unzip(zipFile: potentialZip, dir: 'Resources/shaders/bin/', quiet: true)
            return true
        }
    }
    return false
}

def getArchiveDir() {
    if(isRenderingRegressionMaster) {
        return regressionMasterArchive
    } else if(isRenderingRegressionRelease) {
        return regressionReleaseArchive
    } else if(isFpsTest) {
        return utils.getArchiveDirAndEnsureItExists(platform, 'fps-test')
    } else {
        return utils.getArchiveDirAndEnsureItExists(platform, isRenderingRegressionComparison ? 'rendering-regression/' : '')
    }
}

boolean isUiTest(String fileName) {
    return fileName.startsWith('ui_') || fileName.contains('_ui.') || fileName.contains('_ui_')
}

def doTest() {
    String testDir = checkoutDir + "tests"
    echo "Running tests in ${testDir}"
    dir(testDir) {
        try {
            def app = "X-Plane" + utils.app_suffix + utils.chooseByPlatformMacWinLin([".app/Contents/MacOS/X-Plane" + utils.app_suffix, ".exe", '-x86_64'], platform)
            def binSubdir = utils.chooseByPlatformNixWin("bin", "Scripts", platform)
            def venvPath = utils.isMac(platform) ? '/usr/local/bin/' : ''
            List testsToRun = []
            if(override_test_cmd) {
                testsToRun.push(override_test_cmd)
            } else if(isFpsTest) {
                testsToRun.push('fps_test_runner.py')
            } else if(isRenderingRegression) {
                testsToRun.push('test_runner.py rendering_regression.test --nodelete')
            } else if(isTimeTest) {
                testsToRun.push("load_time_test_runner.py")
            } else {  // Normal integration tests... we'll read jenkins_tests.list to get the files to test
                def testFiles = readListFile('jenkins_tests.list')
                for(String testFile : testFiles) {
                    testsToRun << "test_runner.py ${testFile} --nodelete" + (isUiTest(testFile) ? ' --ui' : '')
                }
                echo 'tests/jenkins_tests.list requests the following tests:\n - ' + testFiles.join('\n - ')
            }
            String setupVenv = "${venvPath}virtualenv env -p ${venvPath}python3.6 && env/${binSubdir}/pip3 install -r package_requirements.txt"
            echo setupVenv
            sh setupVenv

            def errorToThrow = null
            for(String testToRun : testsToRun) {
                String completeCommand = "env/${binSubdir}/python3 ${testToRun} --app ../${app}"
                echo "Running: ${completeCommand}"
                try {
                    sh completeCommand
                } catch(e) {
                    echo "Test ${testsToRun} exited with error, but we won't actually die until all test scripts have completed. Error was: ${e}"
                    errorToThrow = e // Continue running the rest of the tests!
                }
            }

            if(errorToThrow) {
                throw errorToThrow
            }
        } catch(e) {
            echo "Caught error: ${e}"
            try {
                dir(checkoutDir) {
                    String logDest = "Log_${platform}_failed.txt"
                    utils.moveFilePatternToDest("Log.txt", logDest)
                    logFilesToArchive.push(logDest)
                }
            } catch(e2) { }

            def commitId = utils.getCommitId(platform)
            utils.sendEmail("Testing failed on ${platform} [${branch_name}; ${commitId}]",
                    "Auto-testing of commit ${commitId} from the branch ${branch_name} failed.",
                    e.toString())
            throw e
        }
    }

    if(isRenderingRegressionComparison) { // Post-test, we need to run the golden image comparison
        dir(checkoutDir + 'scripts') {
            try {
                sh "python golden_image_regression.py ${regressionMasterArchive} ../regression_images ../master_comparison.txt"
                sh "python golden_image_regression.py ${regressionReleaseArchive} ../regression_images ../release_comparison.txt"
            } catch(e) {
                def commitId = utils.getCommitId(platform)
                utils.sendEmail("Rendering regression image analysis failed on ${platform} [${branch_name}; ${commitId}]",
                        "Running the rendering regression of commit ${commitId} from the branch ${branch_name} succeeded on ${platform}, but the image analysis failed.",
                        e.toString(), "tyler@x-plane.com")
                throw e
            }
        }
    }
}

def doArchive() {
    try {
        dir(checkoutDir) {
            List products = logFilesToArchive
            try {
                if(isFpsTest) {
                    String dest = "fps_test_results_${platform}_${cpu}_${gpu}.csv"
                    utils.moveFilePatternToDest("fps_test_results.csv", dest)
                    products.push(dest)
                } else if(isRenderingRegression) {
                    String zipName = "regression_images_${platform}.zip"
                    String cmd = "zip -r ${zipName} regression_images/*"
                    echo cmd
                    sh cmd
                    products.push(zipName)
                    if(isRenderingRegressionComparison) {
                        products.push('master_comparison.txt')
                        products.push('release_comparison.txt')
                    }
                } else if(isTimeTest) {
                    products.push('load_test_results.txt')
                } else { // Need to read the list of all screenshots to check for
                    for(String screenshotName : readListFile('tests/jenkins_screenshots.list')) {
                        def dest = "${screenshotName}_${platform}.png"
                        try {
                            utils.moveFilePatternToDest("${screenshotName}_1.png", dest)
                            products.push(dest)
                        } catch(e) { } // No error if it doesn't exist
                    }
                }
            } finally {
                // First archive all the truly *required* stuff.
                // any failure here (i.e., a missing *required* artifact) causes a test failure!
                def archiveDir = getArchiveDir()
                archiveWithDropbox(products, archiveDir, false, utils)

                List extraFilePatterns = [
                        // XPD-9229: Any time a wait/expect fails, we take a screenshot before quitting.
                        // This allows us to archive any screenshots we did *not* expect from the jenkins_screenshots.list.
                        // A failure in archiving any of these does *not* result in a test failure.
                        '*.png',
                        // Grab any log files that the test_runner gave us from instances that crashed
                        'Log_crashed_*.png']
                for(String pattern : extraFilePatterns) {
                    for(def file : findFiles(glob: pattern)) {
                        if(!products.contains(file.name)) {
                            try {
                                archiveWithDropbox([file.name], archiveDir, false, utils)
                                products.push(file.name)
                            } catch(e) { }
                        }
                    }
                }
            }
        }
    } catch (e) {
        if(utils.send_emails) {
            notify("Jenkins archive step failed for test on ${platform} [${branch_name}]",
                    "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing screenshot(s) from the automated tests. (Possibly due to a crash?)",
                    e.toString(), "tyler@x-plane.com")
        }
        throw e
    }
}

List<String> readListFile(String fileName) {
    def completeFile = readFile(fileName).replace('\r\n', '\n').replace('\r', '\n') // Turn Windows-style line feeds into plain \n
    def out = []
    for(String line : completeFile.split('\n')) {
        line = line.trim()
        if(line && !line.startsWith('#')) {
            if(line.contains(':')) {
                platformAndTest = line.split(':')
                if(platformAndTest[0] == platform) {
                    out << platformAndTest[1].trim()
                }
            } else { // this is an unqualified test
                out << line
            }
        }
    }
    return out
}



