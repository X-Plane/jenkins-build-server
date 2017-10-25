// If this is an email-triggered test, the branch/tag/commit to test is in the email's subject line
branch_name = pmt_subject ? pmt_subject.trim() : branch_name


def environment = [:]
environment['branch_name'] = branch_name
environment['directory_suffix'] = directory_suffix
environment['release_build'] = release_build
environment['steam_build'] = 'false'
environment['build_windows'] = 'true'
environment['build_mac'] = 'true'
environment['build_linux'] = 'true'
environment['build_all_apps'] = 'false'
utils.setEnvironment(environment)

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
if(pmt_subject && pmt_from) {
    stage('Respond')         { replyToTrigger("Automated testing of commit ${pmt_subject} is in progress on ${platform}.") }
}
stage('Checkout')            { node(nodeType) { doCheckout() } }
try {
    stage('Test')            { node(nodeType) { doTest() } }
} finally { // we want to archive regardless of whether the tests passed
}
    stage('Archive')         { node(nodeType) { doArchive() } }
if(pmt_subject && pmt_from) {
    stage('Notify')          { replyToTrigger("SUCCESS!\n\nThe automated build of commit ${pmt_subject} succeeded on ${platform}.") }
}


//--------------------------------------------------------------------------------------------------------------------------------
// IMPLEMENTATION
//--------------------------------------------------------------------------------------------------------------------------------
def doCheckout() {
    dir(checkoutDir) {
        utils.nukeExpectedProductsIfExist(platform)
    }

    try {
        xplaneCheckout(branch_name, checkoutDir, true, platform)
    } catch(e) {
        notifyTestFailed("Jenkins Git checkout is broken on tester ${platform} [${branch_name}]",
                "${platform} Git checkout failed on branch ${branch_name}. We will be unable to test until this is fixed.",
                e.toString(),
                'tyler@x-plane.com')
        throw e
    }

    // Copy pre-built executables to our working dir as well
    dir(checkoutDir) {
        def products = utils.getExpectedProducts(platform)
        if(utils.copyBuildProductsFromArchive(products, platform)) {
            echo "Copied executables for ${platform} in ${archiveDir}"
        } else {
            def commitId = utils.getCommitId(platform)
            def prodStr = products.join(', ')
            notifyTestFailed(
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
            def cmd = "${venvPath}virtualenv env && env/${binSubdir}/pip install -r package_requirements.txt && env/${binSubdir}/python ${testToRun} --app ../${app}"
            echo cmd
            sh cmd
        } catch(e) {
            if(pmt_subject) {
                replyToTrigger("Automated testing of commit ${pmt_subject} failed on ${platform}.", e.toString())
            } else {
                def commitId = utils.getCommitId(platform)
                notifyTestFailed("Testing failed on ${platform} [${branch_name}; ${commitId}]",
                        "Auto-testing of commit ${commitId} from the branch ${branch_name} failed.",
                        e.toString(), "tyler@x-plane.com")
            }
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
                } else {
                    def commitId = utils.getCommitId(platform)
                    notifyTestFailed("Rendering regression image analysis failed on ${platform} [${branch_name}; ${commitId}]",
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

                if(isRenderingRegression) {
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
                archiveArtifacts artifacts: products.join(', '), fingerprint: true, onlyIfSuccessful: false
                def dest = utils.escapeSlashes(archiveDir)
                for(String p : products) {
                    // Unlike in the builder, we don't need to treat test products as write-once
                    utils.moveFilePatternToDest(p, dest)
                }
            }
        }
    } catch (e) {
        notifyTestFailed("Jenkins archive step failed for test on ${platform} [${branch_name}]",
                "Archive step failed on ${platform}, branch ${branch_name}. This is probably due to missing screenshot(s) from the automated tests. (Possibly due to a crash?)",
                e.toString(), "tyler@x-plane.com")
        throw e
    }
}

def replyToTrigger(String msg, String errorMsg='') {
    notifyTestFailed("Re: " + pmt_subject, msg, errorMsg, pmt_from)
}

def notifyTestFailed(String subj, String msg, String errorMsg, String recipient="") { // empty recipient means we'll send to the most likely suspects
    def summary = errorMsg.isEmpty() ?
            "Download the test products: ${BUILD_URL}artifact/*zip*/archive.zip" :
            "The error was: ${errorMsg}"

    def body = """${msg}
    
${summary}
        
Test URL: ${BUILD_URL}

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

