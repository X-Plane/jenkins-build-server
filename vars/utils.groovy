def setEnvironment(environment, notifyStep, globalSteps=null) {
    assert environment['branch_name'], "Missing expected build parameter: branch_name"
    // Note: because these are strings ("true" or "false"), not actual bools, they'll always evaluate to true
    assert environment['build_windows'] && environment['build_mac'] && environment['build_linux'], "Missing expected build parameters: platforms"
    assert environment['release_build'], "Missing expected build parameters: release_build"
    notify = notifyStep
    branch_name = environment['branch_name']
    send_emails = toRealBool(environment['send_emails'])
    pmt_subject = environment['pmt_subject']
    pmt_from = environment['pmt_from']
    directory_suffix = environment['directory_suffix']
    release_build = toRealBool(environment['release_build'])
    steam_build = toRealBool(environment['steam_build'])
    build_windows = toRealBool(environment['build_windows'])
    build_mac = toRealBool(environment['build_mac'])
    build_linux = toRealBool(environment['build_linux'])
    build_all_apps = toRealBool(environment['build_all_apps'])
    is_release = steam_build || release_build
    is_dev = toRealBool(environment['dev_build'])
    assert !(is_dev && is_release), "Dev and release options are mutually exlusive"
    app_suffix = is_release ? "" : (is_dev ? "_DEV_OPT" : "_NODEV_OPT")
    assert build_all_apps || (!release_build && !steam_build), "Release & Steam builds require all apps to be built"

    node = globalSteps ? globalSteps.&node : null
    parallel = globalSteps ? globalSteps.&parallel : null
}

def replyToTrigger(String msg, String errorMsg='') {
    if(send_emails && pmt_subject && pmt_from) {
        sendEmail("Re: ${pmt_subject}", msg, errorMsg, pmt_from)
    }
}

def do3PlatformStage(String stageName, Closure c) {
    assert node && parallel, 'Failed to pass global steps into utils.setEnvironment()'
    def closure = c
    stage(stageName) {
        parallel(
                'Windows' : { if(build_windows) { node('windows') { closure('Windows') } } },
                'macOS'   : { if(build_mac)     { node('mac')     { closure('macOS')   } } },
                'Linux'   : { if(build_linux)   { node('linux')   { closure('Linux')   } } }
        )
    }
}

def sendEmail(String subj, String msg, String errorMsg='', String recipient='') {
    if(send_emails) {
        if(pmt_subject && pmt_from) {
            notify("Re: ${pmt_subject}", msg, errorMsg, recipient ? recipient : pmt_from)
        } else {
            notify(subj, msg, errorMsg, recipient)
        }
    }
}


String getCheckoutDir(String platform='') {
    return chooseByPlatformNixWin("/jenkins/design-${directory_suffix}/", "C:\\jenkins\\design-${directory_suffix}\\", platform)
}

String getCommitId(String platform='') {
    if((platform && !isWindows(platform)) || isUnix()) {
        return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
    } else {
        def out = bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")
        assert out.size() == 2
        return out[1]
    }
}

String getArchiveRoot(String platform='') {
    return chooseByPlatformNixWin("/jenkins/Dropbox/jenkins-archive/", "D:\\Dropbox\\jenkins-archive\\", platform)
}

String getArchiveDir(String platform='', String optionalSubdir='') {
    String archiveRoot = getArchiveRoot(platform)
    def dirChar = chooseByPlatformNixWin('/', '\\', platform)
    String steamSubdir = steam_build ? "steam${dirChar}" : ""
    if(optionalSubdir) {
        optionalSubdir += dirChar
    }
    String commitDir = getCommitId(platform)
    if(is_release) { // stick it in a directory named based on the commit/tag/branch name that triggered the build
        commitDir = branch_name + '-' + commitDir
    }
    String archiveDir = chooseByPlatformNixWin("${archiveRoot}${steamSubdir}${optionalSubdir}${commitDir}/", "${archiveRoot}${steamSubdir}${optionalSubdir}${commitDir}\\", platform)
    assert archiveDir : "Got an empty archive dir"
    assert !archiveDir.contains("C:") || isWindows(platform) : "Got a Windows path on platform ${platform} from getArchiveDir()"
    assert !archiveDir.contains("/jenkins/") || isNix(platform) : "Got a Unix path on Windows from utils.getArchiveDir()"
    return archiveDir
}

String getArchiveDirAndEnsureItExists(String platform='', String optionalSubdir='') {
    String out = getArchiveDir(platform, optionalSubdir)
    try {
        chooseShellByPlatformNixWin("mkdir ${out}", "mkdir \"${out}\"")
    } catch(e) { } // ignore errors if it already exists
    return out
}

List getExpectedXPlaneProducts(String platform) {
    String appExtNormal = chooseByPlatformMacWinLin([".app.zip", ".exe", '-x86_64'], platform)
    List appNamesNoInstaller = addSuffix(build_all_apps ? ["X-Plane", "Airfoil Maker", "Plane Maker"] : ["X-Plane"], app_suffix)
    List appNames = appNamesNoInstaller + (build_all_apps ? ["X-Plane 11 Installer" + app_suffix] : [])
    List filesWithExt = addSuffix(appNamesNoInstaller, appExtNormal)
    if(build_all_apps) {
        if(isMac(platform) || isWindows(platform)) {
            filesWithExt.push("X-Plane 11 Installer" + app_suffix + appExtNormal)
        } else {
            filesWithExt.push("X-Plane 11 Installer" + app_suffix)
        }
    }

    if(is_release) {
        def symbolsSuffix = chooseByPlatformMacWinLin(['.app.dSYM.zip', '_win.sym', '_lin.sym'], platform)
        def platformOther = addSuffix(chooseByPlatformMacWinLin([["X-Plane"], appNames, filesWithExt], platform), symbolsSuffix)
        if(isWindows(platform)) {
            platformOther += addSuffix(appNames, ".pdb")
        }
        filesWithExt += platformOther
    }
    return filesWithExt
}

def nukeIfExist(List<String> files, String platform) {
    for(def f : files) {
        try {
            chooseShellByPlatformNixWin("rm -Rf ${f}", "del \"${f}\"", platform)
        } catch(e) { } // No old executables lying around? No problem!
    }
}

boolean copyBuildProductsFromArchive(List expectedProducts, String platform) {
    String archiveDir = getArchiveDir()
    List archivedProductPaths = addPrefix(expectedProducts, archiveDir)
    if(filesExist(archivedProductPaths)) {
        // Copy them back to our working directories for the sake of working with them
        chooseShellByPlatformNixWin("cp ${archiveDir}* .", "copy \"${archiveDir}*\" .", platform)
        if(isMac(platform)) {
            sh "unzip -o '*.zip'" // single-quotes necessary so that the silly unzip command doesn't think we're specifying files within the first expanded arg
        }
        return true
    }
    return false
}

def getBuildToolConfiguration() {
    return steam_build ? "NODEV_OPT_Prod_Steam" : (release_build ? "NODEV_OPT_Prod" : "NODEV_OPT")
}

// $&@#* Jenkins.
// It passes us our BOOLEAN parameters as freaking strings. "false" and "true".
// So, if you try to, oh I don't know, USE THEM LIKE YOU WOULD A BOOLEAN,
// the string "false" evaluates to TRUE!!!!!
// "But Tyler," you say, "why don't you just do foo = toRealBool(foo) at the top of the script and be done with it?"
// Great question.
// Because you also CAN'T CHANGE A VARIABLE'S TYPE AFTER IT'S BEEN CREATED.
boolean toRealBool(fakeBool) {
    return fakeBool == 'true'
}


//----------------------------------------------------------------------------------------------------------------------------------------------------------
// PLATFORM UTILS
//----------------------------------------------------------------------------------------------------------------------------------------------------------
def supportsTesting() {
    return isUnix() && !steam_build
}

def isWindows(String platform) {
    return platform == 'Windows'
}
def isNix(String platform) {
    return !isWindows(platform)
}
def isMac(String platform) {
    return platform == 'macOS'
}

def chooseByPlatformNixWin(nixVersion, winVersion, String platform='') {
    if(platform) {
        return chooseByPlatformMacWinLin([nixVersion, winVersion, nixVersion], platform)
    } else if(isUnix()) {
        return nixVersion
    } else {
        return winVersion
    }
}
def chooseByPlatformMacWinLin(macWinLinOptions, String platform) {
    assert macWinLinOptions.size() == 3 : "Got the wrong number of options to choose by platform"
    if(isMac(platform)) {
        return macWinLinOptions[0]
    } else if(isWindows(platform)) {
        return macWinLinOptions[1]
    } else {
        assert isNix(platform) : "Got unknown platform ${platform} in chooseByPlatformMacWinLin()"
        return macWinLinOptions[2]
    }
}

//----------------------------------------------------------------------------------------------------------------------------------------------------------
// STRING UTILS
//----------------------------------------------------------------------------------------------------------------------------------------------------------
String escapeSlashes(String path) {
    if(isUnix()) {
        assert !path.contains("\\ ")
        return path.replace(" ", "\\ ")
    } else {
        return path
    }
}

def addPrefix(List strings, String newPrefix) {
    // Someday, when Jenkins supports the .collect function... (per JENKINS-26481)
    // return strings.collect({ s + newPrefix })
    def out = []
    for(def s : strings) {
        out += newPrefix + s
    }
    return out
}
def addSuffix(List strings, String newSuffix) {
    // Someday, when Jenkins supports the .collect function... (per JENKINS-26481)
    // return strings.collect({ newSuffix + s })
    def out = []
    for(def s : strings) {
        out += s + newSuffix
    }
    return out
}


//----------------------------------------------------------------------------------------------------------------------------------------------------------
// FILE OPS
//----------------------------------------------------------------------------------------------------------------------------------------------------------
def filesExist(List expectedProducts) {
    for(def p : expectedProducts) {
        if(!fileExists(p)) {
            return false
        }
    }
    return true
}

def moveFilePatternToDest(String filePattern, String dest) {
    chooseShellByPlatformNixWin("mv \"$filePattern\" \"${dest}\"",  "move /Y \"${filePattern}\" \"${dest}\"")
}


//----------------------------------------------------------------------------------------------------------------------------------------------------------
// SHELLS
//----------------------------------------------------------------------------------------------------------------------------------------------------------
def chooseShellByPlatformNixWin(String nixCommand, String winCommand, String platform='') {
    if(platform) {
        chooseShellByPlatformMacWinLin([nixCommand, winCommand, nixCommand], platform)
    } else if(isUnix()) {
        sh nixCommand
    } else {
        bat winCommand
    }
}
def chooseShellByPlatformMacWinLin(List macWinLinCommands, String platform) {
    if(isWindows(platform)) {
        bat macWinLinCommands[1]
    } else if(isMac(platform)) {
        sh macWinLinCommands[0]
    } else {
        sh macWinLinCommands[2]
    }
}

