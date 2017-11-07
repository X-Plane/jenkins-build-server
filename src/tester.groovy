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
utils.setEnvironment(environment, steps)

isFpsTest = test_type == 'fps_test'
isSmokeTest = test_type == 'smoke_test'
isRenderingRegressionMaster = test_type == 'rendering_regression_new_master'
isRenderingRegressionComparison = test_type == 'rendering_regression_compare'
isRenderingRegression = isRenderingRegressionMaster || isRenderingRegressionComparison
expectedScreenshotNames = isSmokeTest ? ["sunset_scattered_clouds", "evening", "stormy"] : []
String nodeType = platform == 'Windows' ? 'windows' : (platform == 'Linux' ? 'linux' : 'mac')
node(nodeType) {
    checkoutDir = utils.getCheckoutDir(platform)
    renderingRegressionMaster = utils.getArchiveRoot(platform) + 'rendering-master/'
    archiveDir = isRenderingRegressionMaster ?
            renderingRegressionMaster :
            utils.getArchiveDir(platform) + (isRenderingRegressionComparison ? 'rendering-regression/' : '')
}

//--------------------------------------------------------------------------------------------------------------------------------
// RUN THE TESTS
// This is where the magic happens.
//--------------------------------------------------------------------------------------------------------------------------------
stage('Respond')             { utils.replyToTriggerF("Automated testing of commit ${branch_name} is in progress on ${platform}.") }
stage('Checkout')            { node(nodeType) { doCheckout() } }
try {
    stage('Test')            { node(nodeType) { doTest() } }
} finally { // we want to archive regardless of whether the tests passed
    stage('Archive')         { node(nodeType) { doArchive() } }
}
stage('Notify')              { utils.replyToTriggerF("SUCCESS!\n\nThe automated build of commit ${branch_name} succeeded on ${platform}.") }


//--------------------------------------------------------------------------------------------------------------------------------
// IMPLEMENTATION
//--------------------------------------------------------------------------------------------------------------------------------
def doCheckout() {
    // Nuke previous products
    cleanCommand = utils.toRealBool(clean_build) ? ['rm -Rf design_xcode', 'rd /s /q design_vstudio', 'rm -Rf design_linux'] : []
    clean(utils.getExpectedXPlaneProducts(platform) + ['*.png'], cleanCommand, platform, utils)

    try {
        xplaneCheckout(branch_name, checkoutDir, platform)
        getArt()
    } catch(e) {
        notifyBrokenCheckout(utils.sendEmailF, 'autotesting', branch_name, platform, e)
    }

    // Copy pre-built executables to our working dir as well
    dir(checkoutDir) {
        def products = utils.getExpectedXPlaneProducts(platform)
        if(utils.copyBuildProductsFromArchive(products, platform)) {
            echo "Copied executables for ${platform} in ${archiveDir}"
        } else {
            def commitId = utils.getCommitId(platform)
            def prodStr = products.join(', ')
            utils.sendEmailF(
                    "Testing failed on ${platform} [${branch_name}; ${commitId}]",
                    "Missing executables to test on ${platform} [${branch_name}]",
                    "Couldn't find pre-built binaries to test for ${platform} on branch ${branch_name}.\r\n\r\nWe were looking for:\r\n${prodStr}\r\nin directory:\r\n${archiveDir}\r\n\r\nWe will be unable to test until this is fixed.",
                    'tyler@x-plane.com')
            throw new java.io.FileNotFoundException()
        }
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
            String testToRun = ''
            if(isFpsTest) {
                testToRun = 'fps_test_runner.py'
            } else {
                String testFile = isRenderingRegression ? 'rendering_regression.test' : 'jenkins_smoke_test.test'
                testToRun = "test_runner.py ${testFile} --nodelete"
            }
            String setupVenv = "${venvPath}virtualenv env && env/${binSubdir}/pip install -r package_requirements.txt"
            echo setupVenv
            sh setupVenv
            String runTest = override_test_cmd ? override_test_cmd : "env/${binSubdir}/python ${testToRun} --app ../${app}"
            echo runTest
            sh runTest
        } catch(e) {
            def commitId = utils.getCommitId(platform)
            utils.sendEmailF("Testing failed on ${platform} [${branch_name}; ${commitId}]",
                    "Auto-testing of commit ${commitId} from the branch ${branch_name} failed.",
                    e.toString())
            throw e
        }
    }

    if(isRenderingRegression) { // Post-test, we need to run the golden image comparison
        dir(checkoutDir) {
            try {
                // TODO: Do the image analysis
            } catch(e) {
                if(pmt_subject) {
                    replyToTrigger("Rendering regression image analysis of commit ${pmt_subject} failed on ${platform}.", e.toString())
                } else if(utils.send_emails) {
                    def commitId = utils.getCommitId(platform)
                    notify("Rendering regression image analysis failed on ${platform} [${branch_name}; ${commitId}]",
                            "Running the rendering regression of commit ${commitId} from the branch ${branch_name} succeeded on ${platform}, but the image analysis failed.",
                            e.toString(), "tyler@x-plane.com")
                }
            }
        }
    }
}

def doArchive() {
    try {
        dir(checkoutDir) {
            List products = []
            try {
                for(String screenshotName : expectedScreenshotNames) {
                    def dest = "${screenshotName}_${platform}.png"
                    try {
                        utils.moveFilePatternToDest("${screenshotName}_1.png", dest)
                        products.push(dest)
                    } catch(e) { } // No error if it doesn't exist
                }

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
                }

                def logDest = "Log_${platform}.txt"
                utils.moveFilePatternToDest("Log.txt", logDest)
                products.push(logDest)
            } finally {
                archiveWithDropbox(products, archiveDir, false, utils)
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

def replyToTrigger(String msg, String errorMsg='') {
    if(utils.send_emails) {
        notify("Re: " + pmt_subject, msg, errorMsg, pmt_from)
    }
}


