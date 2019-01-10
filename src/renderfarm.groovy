// Globals
// rendering_code_branch_name
// xptools_branch_name
// build_for_mobile ("bool")
// build_type (DebugOpt|Debug|Release)

def environment = [:]
environment['branch_name'] = xptools_branch_name
environment['send_emails'] = send_emails
environment['build_type'] = build_type
environment['build_windows'] = 'false'
environment['build_mac'] = 'true'
environment['build_linux'] = 'false'
environment['build_all_apps'] = 'true'
utils.setEnvironment(environment, this.&notify, this.steps)

//String nodeType = utils.isWindows(platform) ? 'windows' : (utils.isMac(platform) ? 'mac' : 'linux')
String nodeType = 'renderfarm'

xptools_directory = '/jenkins/xptools'
rendering_code_directory = '/jenkins/rendering_code'

stage('Checkout xptools')        { node(nodeType) { checkoutXpTools(platform) } }
stage('Build xptools')           { node(nodeType) { buildXpTools(platform) } }
stage('Archive RenderFarm')      { node(nodeType) { archiveRenderFarm(platform) } }
stage('Checkout rendering_code') { node(nodeType) { checkoutRenderingCode(platform) } }
stage('Build DSFs')              { node(nodeType) { buildDsfs(platform) } }
stage('Archive DSFs')            { node(nodeType) { archiveDsfs(platform) } }

String getXpToolsDir(platform)       { return utils.getJenkinsDir('xptools',        platform) }
String getRenderingCodeDir(platform) { return utils.getJenkinsDir('rendering_code', platform) }

def checkoutXpTools(String platform) {
    try {
        xplaneCheckout(xptools_branch_name, getXpToolsDir(platform), platform, 'https://github.com/X-Plane/xptools.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'RenderFarm (xptools)', xptools_branch_name, platform, e)
        throw e
    }
}

def buildXpTools(String platform) {
    // TODO: Add arch argument to the compiler for the fastest builds possible
    dir(getXpToolsDir(platform)) {
        try {
            String projectFile = utils.chooseByPlatformNixWin("SceneryTools_xcode6.xcodeproj", "msvc\\XPTools.sln", platform)
            String xcodebuildBoilerplate = "set -o pipefail && xcodebuild -target RenderFarm -config ${build_type} -project ${projectFile}"
            String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} clean ${pipe_to_xcpretty}",
                    "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                    'make clean'
            ], platform)

            utils.chooseShellByPlatformMacWinLin([
                    "${xcodebuildBoilerplate} build ${pipe_to_xcpretty}",
                    "\"${tool 'MSBuild'}\" /t:RenderFarm /m /p:Configuration=\"${build_type}\" ${projectFile}",
                    "make -s -C . conf=${build_type} RenderFarm"
            ], platform)

            if(utils.isMac(platform)) {
                sh "${xcodebuildBoilerplate} archive ${pipe_to_xcpretty}"
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'RenderFarm', xptools_branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def archiveRenderFarm(String platform) {
    try {
        dir(getXpToolsDir(platform)) {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            if(utils.isMac(platform)) {
                zip(zipFile: 'RenderFarm.app.zip', archive: false, dir: 'RenderFarm.xcarchive/Products/Applications/RenderFarm.app')
            }
            def productPaths = utils.addPrefix(getExpectedXpToolsProducts(platform), utils.chooseByPlatformMacWinLin(['', 'msvc\\RenderFarm\\', "build/Linux/${build_type}/"], platform))
            archiveWithDropbox(productPaths, utils.getArchiveDirAndEnsureItExists(platform, 'RenderFarm'), true, utils, false)

            // Copy into place for rendering_code scripts to find
            String rcBuildDir = getRenderingCodeDir(platform) + build_type + utils.getDirChar(platform)
            for(String p : products) {
                utils.copyFilePatternToDest(p, rcBuildDir)
            }
            dir(rcBuildDir) {
                for(def file : findFiles(glob: '*.zip')) {
                    unzip(zipFile: file, quiet: true)
                }
            }
        }
    } catch (e) {
        utils.sendEmail("RenderFarm executable archive step failed on ${platform} [${xptools_branch_name}]",
                "Archive step for the RenderFarm executable failed on ${platform}, branch ${xptools_branch_name}. This is probably due to missing the WED executable.",
                e.toString())
        throw e
    }
}

List<String> getExpectedXpToolsProducts(String platform) {
    return [utils.chooseByPlatformMacWinLin(['RenderFarm.app.zip', 'RenderFarm.exe', 'RenderFarm'], platform)]
}

def checkoutRenderingCode(String platform) {
    try {
        xplaneCheckout(rendering_code_branch_name, getRenderingCodeDir(platform), platform, 'ssh://dev.x-plane.com/admin/git-xplane/rendering_code.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'rendering_code', rendering_code_branch_name, platform, e)
        throw e
    }

}

def buildDsfs(String platform) {
    dir(getRenderingCodeDir(platform)) {
        try {
            sh './clean_output.sh'
            sh './run_block_multi.sh -180 -80 179 73 $(nproc) ./make_world_one_final.sh --quiet 2>> errors.txt'
            sh './run_block_multi.sh -180 -80 179 73 $(nproc) ./zip_dsf.sh'
            sh 'tar -cf ../rendering_data/OUTPUT-dsf.tar ../rendering_data/OUTPUT-dsf'
            sh 'find OUTPUT-dsf -type f | wc -l'
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'DSF build', rendering_code_branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

