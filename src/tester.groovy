// If this is an email-triggered test, the branch/tag/commit to test is in the email's subject line
branch_name = pmt_subject ? pmt_subject.trim() : branch_name


def environment = [:]
environment['branch_name'] = branch_name
environment['send_emails'] = send_emails
environment['pmt_subject'] = pmt_subject
environment['pmt_from'] = pmt_from
environment['directory_suffix'] = directory_suffix
environment['release_build'] = release_build
environment['steam_build'] = 'false'
environment['build_windows'] = 'true'
environment['build_mac'] = 'true'
environment['build_linux'] = 'true'
environment['build_all_apps'] = 'false'
environment['dev_build'] = dev_build
utils.setEnvironment(environment, this.&notify)

isFpsTest = test_type == 'fps_test'
isSmokeTest = test_type == 'smoke_test'
isRenderingRegressionMaster = test_type == 'rendering_regression_new_master'
isRenderingRegressionRelease = test_type == 'rendering_regression_new_release'
isRenderingRegressionComparison = test_type == 'rendering_regression_compare'
isRenderingRegression = isRenderingRegressionMaster || isRenderingRegressionRelease || isRenderingRegressionComparison
regressionMasterArchive = utils.getArchiveRoot(platform) + 'rendering-master/'
regressionReleaseArchive = utils.getArchiveRoot(platform) + 'rendering-release/'
String nodeType = platform == 'Windows' ? 'windows' : (platform == 'Linux' ? 'linux' : 'mac')
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
    stage('Test')            { node(nodeType) { doTest() } }
} finally { // we want to archive regardless of whether the tests passed
    stage('Archive')         { node(nodeType) { doArchive() } }
}
stage('Notify')              { utils.replyToTrigger("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded on ${platform}.") }


//--------------------------------------------------------------------------------------------------------------------------------
// IMPLEMENTATION
//--------------------------------------------------------------------------------------------------------------------------------
def doCheckout() {
    // Nuke previous products
    cleanCommand = utils.toRealBool(clean_build) ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(utils.getExpectedXPlaneProducts(platform) + ['*.png'], cleanCommand, platform, utils)

    try {
        xplaneCheckout(branch_name, checkoutDir, platform)
        getArt(checkoutDir)
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'autotesting', branch_name, platform, e)
    }

    // Copy pre-built executables to our working dir as well
    dir(checkoutDir) {
        def products = utils.getExpectedXPlaneProducts(platform)
        archiveDir = getArchiveDir()
        if(utils.copyBuildProductsFromArchive(products, platform)) {
            echo "Copied executables for ${platform} in ${archiveDir}"
        } else {
            def commitId = utils.getCommitId(platform)
            def prodStr = products.join(', ')
            utils.sendEmail(
                    "Testing failed on ${platform} [${branch_name}; ${commitId}]",
                    "Missing executables to test on ${platform} [${branch_name}]",
                    "Couldn't find pre-built binaries to test for ${platform} on branch ${branch_name}.\r\n\r\nWe were looking for:\r\n${prodStr}\r\nin directory:\r\n${archiveDir}\r\n\r\nWe will be unable to test until this is fixed.",
                    'tyler@x-plane.com')
            throw new java.io.FileNotFoundException()
        }
    }
}

def getArchiveDir() {
    if(isRenderingRegressionMaster) {
        return regressionMasterArchive
    } else if(isRenderingRegressionRelease) {
        return regressionReleaseArchive
    } else {
        return utils.getArchiveDir(platform) + (isRenderingRegressionComparison ? 'rendering-regression/' : '')
    }
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
            } else {  // Normal integration tests... we'll read jenkins_tests.list to get the files to test
                def testFiles = readListFile('jenkins_tests.list')
                for(String testFile : testFiles) {
                    testsToRun << "test_runner.py ${testFile} --nodelete"
                }
                echo 'tests/jenkins_tests.list requests the following tests:\n - ' + testFiles.join('\n - ')
            }
            String setupVenv = "${venvPath}virtualenv env && env/${binSubdir}/pip install -r package_requirements.txt"
            echo setupVenv
            sh setupVenv

            def errorToThrow = null
            for(String testToRun : testsToRun) {
                String completeCommand = "env/${binSubdir}/python ${testToRun} --app ../${app}"
                echo "Running: ${completeCommand}"
                try {
                    sh completeCommand
                } catch(e) {
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
                    String dest = "fps_test_results_${platform}.txt"
                    utils.moveFilePatternToDest("fps_test_results.txt", dest)
                    products.push(dest)
                } else if(isRenderingRegression) {
                    String zipName = "regression_images.zip"
                    String cmd = "zip -r ${zipName} regression_images/*"
                    echo cmd
                    sh cmd
                    products.push(zipName)

                    if(isRenderingRegressionComparison) {
                        products.push('master_comparison.txt')
                        products.push('release_comparison.txt')
                    }
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
                archiveWithDropbox(products, getArchiveDir(), false, utils)
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

List<String> readListFile(fileName) {
    def completeFile = readFile(fileName).normalize() // Turn Windows-style line feeds into plain \n
    def out = []
    for(String line : completeFile.split('\n')) {
        line = line.trim()
        if(line && !line.startsWith('#')) {
            out << line
        }
    }
    return out
}



