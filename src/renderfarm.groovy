// Globals
// rendering_code_branch_name
// xptools_branch_name
// build_for_mobile ("bool")
// build_type (DebugOpt|Debug|Release)
// clean_xptools ("bool")
// clean_dsfs ("bool")
// verbose ("bool")
// lon_min
// lon_max
// lat_min
// lat_max

def environment = [:]
environment['branch_name'] = xptools_branch_name
environment['send_emails'] = send_emails
environment['build_type'] = build_type
environment['build_windows'] = 'false'
environment['build_mac'] = 'true'
environment['build_linux'] = 'false'
environment['build_all_apps'] = 'true'
utils.setEnvironment(environment, this.&notify, this.steps)

String nodeType = 'renderfarm'

xptools_directory = '~/jenkins/xptools'
rendering_code_directory = '~/jenkins/rendering_code'

class NodeSpec {
    static totalClusterThreads = 0
    String name
    String platform
    int threads
}

rfNodes = getNodesToRunOn()

stage('Checkout xptools')        { forEachNode(this.&checkoutXpTools) }
stage('Build xptools')           { forEachNode(this.&buildXpTools) }
stage('Archive RenderFarm')      { forEachNode(this.&archiveRenderFarm) }
stage('Checkout rendering_code') { forEachNode(this.&checkoutRenderingCode) }
try {
    stage('Build DSFs Phase 1')  { forEachNode(this.&buildDsfsPhase0) }
    stage('Build DSFs Phase 2')  { forEachNode(this.&buildDsfsPhase1) }
} finally {
    stage('Archive DSFs')        { forEachNode(this.&archiveDsfs) }
}

String getXpToolsDir(platform)       { return utils.getJenkinsDir('xptools',        platform) }
String getRenderingCodeDir(platform) { return utils.getJenkinsDir('rendering_code', platform) }

def forEachNode(Closure c) {
    def closure = c

    def threadIdx = 0
    def stepsForParallel = [:]
    for(NodeSpec n : rfNodes) {
        stepsForParallel[n.name] = { node(n.name) { closure(threadIdx, threadIdx + n.threads, n.platform) } }
        threadIdx += n.threads
    }

    parallel stepsForParallel
}


def checkoutXpTools(int nodeThreadBegin, int nodeThreadEnd, String platform) {
    try {
        xplaneCheckout(xptools_branch_name, getXpToolsDir(platform), platform, 'https://github.com/X-Plane/xptools.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'RenderFarm (xptools)', xptools_branch_name, platform, e)
        throw e
    }
}

def buildXpTools(int nodeThreadBegin, int nodeThreadEnd, String platform) {
    // TODO: Add arch argument to the compiler for the fastest builds possible
    dir(getXpToolsDir(platform)) {
        try {
            String archiveDir = utils.getArchiveDirAndEnsureItExists(platform, 'RenderFarm')
            if(utils.copyBuildProductsFromArchive(getExpectedXpToolsProductsArchived(platform), platform, 'RenderFarm')) {
                echo "This commit was already built for ${platform} in ${archiveDir}"
            } else {
                String projectFile = utils.chooseByPlatformNixWin("SceneryTools.xcodeproj", "msvc\\XPTools.sln", platform)
                String xcodebuildBoilerplate = "set -o pipefail && xcodebuild -scheme RenderFarm -config ${build_type} -project ${projectFile}"
                String pipe_to_xcpretty = env.NODE_LABELS.contains('xcpretty') ? '| xcpretty' : ''
                if(utils.toRealBool(clean_xptools)) {
                    utils.chooseShellByPlatformMacWinLin([
                            "${xcodebuildBoilerplate} clean ${pipe_to_xcpretty}",
                            "\"${tool 'MSBuild'}\" ${projectFile} /t:Clean",
                            'make clean'
                    ], platform)
                }

                utils.chooseShellByPlatformMacWinLin([
                        "${xcodebuildBoilerplate} -archivePath RenderFarm.xcarchive archive ${pipe_to_xcpretty}",
                        "\"${tool 'MSBuild'}\" /t:RenderFarm /m /p:Configuration=\"${build_type}\" ${projectFile}",
                        "make -s -C . conf=${build_type} RenderFarm"
                ], platform)
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'RenderFarm', xptools_branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def archiveRenderFarm(int nodeThreadBegin, int nodeThreadEnd, String platform) {
    try {
        dir(getXpToolsDir(platform)) {
            // If we're on macOS, the "executable" is actually a directory within an xcarchive directory.. we need to ZIP it, then operate on the ZIP files
            def productPaths = utils.addPrefix(getExpectedXpToolsProducts(platform), utils.chooseByPlatformMacWinLin(['', 'msvc\\RenderFarm\\', "build/Linux/${build_type}/"], platform))
            archiveWithDropbox(productPaths, utils.getArchiveDirAndEnsureItExists(platform, 'RenderFarm'), true, utils, false)

            // Copy into place for rendering_code scripts to find
            String rcBuildDir = getRenderingCodeDir(platform) + build_type + utils.getDirChar(platform)
            for(String p : productPaths) {
                utils.copyFilePatternToDest(p, rcBuildDir)
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
    return [utils.chooseByPlatformMacWinLin(['RenderFarm.xcarchive/Products/usr/local/bin/RenderFarm', 'RenderFarm.exe', 'RenderFarm'], platform)]
}
List<String> getExpectedXpToolsProductsArchived(String platform) {
    return [utils.chooseByPlatformMacWinLin(['RenderFarm', 'RenderFarm.exe', 'RenderFarm'], platform)]
}

def checkoutRenderingCode(int nodeThreadBegin, int nodeThreadEnd, String platform) {
    try {
        xplaneCheckout(rendering_code_branch_name, getRenderingCodeDir(platform), platform, 'ssh://dev.x-plane.com/admin/git-xplane/rendering_code.git')
    } catch(e) {
        notifyBrokenCheckout(utils.&sendEmail, 'rendering_code', rendering_code_branch_name, platform, e)
        throw e
    }

    dir(getRenderingCodeDir(platform)) {
        sh 'rm -f errors.txt'
        if(utils.toRealBool(clean_dsfs)) {
            sh './clean_output.sh'
        }
    }
}

def buildDsfs(int nodeThreadBegin, int nodeThreadEnd, int phase, String platform) {
    dir(getRenderingCodeDir(platform)) {
        String quietFlag = utils.toRealBool(verbose) ? '' : '--quiet'
        def x_global_min = lon_min as Integer
        def x_global_max = lon_max as Integer
        def y_global_min = lat_min as Integer
        def y_global_max = lat_max as Integer

        def x_chunk_size = (x_global_max - x_global_min) / NodeSpec.totalClusterThreads
        def y_chunk_size = (y_global_max - y_global_min) / NodeSpec.totalClusterThreads

        int x_low = x_global_min + floor(nodeThreadBegin * x_chunk_size)
        int y_low = y_global_min + floor(nodeThreadBegin * y_chunk_size)
        int x_high = x_global_min + floor(nodeThreadEnd * x_chunk_size)
        int y_high = y_global_min + floor(nodeThreadEnd * y_chunk_size)
        try {
            echo "This node will run the range from ${x_low} -> ${x_high} and ${y_low} -> ${y_high}"
            sh "./run_block_multi_networked.sh ${x_low} ${y_low} ${x_high} ${y_high} \$(nproc) ${phase} ./make_world_one_final.sh ${quietFlag} --comp_dsf 2>> errors_${env.NODE_NAME}.txt"
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'DSF build', rendering_code_branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

def buildDsfsPhase0(int nodeThreadBegin, int nodeThreadEnd, String platform) { buildDsfs(nodeThreadBegin, nodeThreadEnd, 0, platform) }
def buildDsfsPhase1(int nodeThreadBegin, int nodeThreadEnd, String platform) { buildDsfs(nodeThreadBegin, nodeThreadEnd, 1, platform) }

def archiveDsfs(int nodeThreadBegin, int nodeThreadEnd, String platform) {
    dir(getRenderingCodeDir(platform)) {
        try {
            archiveArtifacts artifacts: ["errors_${env.NODE_NAME}.txt"].join(', '), fingerprint: true, onlyIfSuccessful: false
            if(nodeThreadBegin == 0) {
                sh "tar -cf ../rendering_data/OUTPUT-dsf.tar ../rendering_data/OUTPUT-dsf"
                def fileCount = sh(returnStdout: true, script: 'find ../rendering_data/OUTPUT-dsf -type f | wc -l').trim()
                echo "Found $fileCount DSFs total"
                assert fileCount > 1000
            }
        } catch (e) {
            notifyDeadBuild(utils.&sendEmail, 'DSF archive', rendering_code_branch_name, utils.getCommitId(platform), platform, e)
        }
    }
}

@NonCPS
List<Node> getNodesWithLabel(String label) {
    def nodes = []
    def allNodes = Jenkins.getInstance().getNodes()
    for(def node : allNodes) {
        if (node.labelString.contains(label)) {
            nodes.add(node)
        }
    }
    return nodes
}

@NonCPS
int getThreadsOnNode(Node node) {
    for(int threads : 2..128) {
        if (node.labelString.contains("${threads}-threads")) {
            return threads
        }
    }
    return 0
}

@NonCPS
String inferPlatform(Node node) {
    if(node.labelString.contains('indows')) {
        return 'Windows'
    } else if(node.labelString.contains('inux')) {
        return 'Linux'
    } else {
        return 'macOS'
    }
}

@NonCPS
List<NodeSpec> getNodesToRunOn() {
    List<NodeSpec> out = []
    List<Node> nativeNodes = getNodesWithLabel('renderfarm')
    for(int i = 0; i < nativeNodes.size(); ++i) {
        NodeSpec n = new NodeSpec()
        n.name = nativeNodes[i].getNodeName()
        n.platform = inferPlatform(nativeNodes[i])
        n.threads = getThreadsOnNode(nativeNodes[i])
        n.totalClusterThreads += n.threads
        out.add(n)
    }
    return out
}
