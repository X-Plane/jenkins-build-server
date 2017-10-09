String getCheckoutDir(String directorySuffix) {
    return chooseByPlatformNixWin("/jenkins/design-${directorySuffix}/", "C:\\jenkins\\design-${directorySuffix}\\")
}

String getCommitId(String directorySuffix) {
    dir(getCheckoutDir(directorySuffix)) {
        if(isUnix()) {
            return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        } else {
            def out = bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")
            assert out.size() == 2
            return out[1]
        }
    }
}

String getArchiveDir(String directorySuffix, boolean isSteamBuild) {
    String subdir = isSteamBuild ? chooseByPlatformNixWin("steam/", "steam\\") : ""
    String commitDir = getCommitId(directorySuffix)
    if(isRelease()) { // stick it in a directory named based on the commit/tag/branch name that triggered the build
        commitDir = getBranchName() + '-' + commitDir
    }
    return escapeSlashes(chooseByPlatformNixWin("/jenkins/Dropbox/jenkins-archive/${subdir}${commitDir}/", "D:\\Dropbox\\jenkins-archive\\${subdir}${commitDir}\\"))
}

def getExpectedProducts(String platform, boolean buildAll, boolean releaseBuild) {
    String appExtNormal = chooseByPlatformMacWinLin([".app.zip", ".exe", '-x86_64'], platform)
    String appSuffix = getAppSuffix(releaseBuild)
    List appNamesNoInstaller = addSuffix(buildAll ? ["X-Plane", "Airfoil Maker", "Plane Maker"] : ["X-Plane"], appSuffix)
    List appNames = appNamesNoInstaller + (buildAll ? ["X-Plane 11 Installer" + appSuffix] : [])
    List filesWithExt = addSuffix(appNamesNoInstaller, appExtNormal)
    if(isMac(platform) || isWindows(platform)) {
        filesWithExt.push("X-Plane 11 Installer" + appSuffix + appExtNormal)
    } else {
        filesWithExt.push("X-Plane 11 Installer" + appSuffix)
    }

    if(releaseBuild) {
        def symbolsSuffix = chooseByPlatformMacWinLin(['.app.dSYM.zip', '_win.sym', '_lin.sym'], platform)
        def platformOther = addSuffix(chooseByPlatformMacWinLin([["X-Plane"], appNames, filesWithExt], platform), symbolsSuffix)
        if(isWindows(platform)) {
            platformOther += addSuffix(appNames, ".pdb")
        }
        filesWithExt += platformOther
    }
    return filesWithExt
}
def getAppSuffix(boolean releaseBuild) {
    return releaseBuild ? "" : "_NODEV_OPT"
}

boolean copyBuildProductsFromArchive(String archiveDir, List expectedProducts) {
    List archivedProductPaths = addPrefix(expectedProducts, archiveDir)
    if(filesExist(archivedProductPaths)) {
        // Copy them back to our working directories for the sake of working with them
        chooseShellByPlatformNixWin("cp ${archiveDir}* .", "copy \"${archiveDir}*\" .")
        if(isMac(platform)) {
            sh "unzip -o '*.zip'" // single-quotes necessary so that the silly unzip command doesn't think we're specifying files within the first expanded arg
        }
        return true
    }
    return false
}


// $&@#* Jenkins.
// It passes us our BOOLEAN parameters as freaking strings. "false" and "true".
// So, if you try to, oh I don't know, USE THEM LIKE YOU WOULD A BOOLEAN,
// the string "false" evaluates to TRUE!!!!!
// "But Tyler," you say, "why don't you just do foo = toRealBool(foo) at the top of the script and be done with it?"
// Great question.
// Because you also CAN'T CHANGE A VARIABLE'S TYPE AFTER IT'S BEEN CREATED.
boolean toRealBool(String fakeBool) {
    return fakeBool == 'true'
}


//----------------------------------------------------------------------------------------------------------------------------------------------------------
// PLATFORM UTILS
//----------------------------------------------------------------------------------------------------------------------------------------------------------
def supportsTesting(boolean isSteamBuild) {
    return isUnix() && !isSteamBuild
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

def chooseByPlatformNixWin(nixVersion, winVersion) {
    if(isUnix()) {
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
def chooseShellByPlatformNixWin(nixCommand, winCommand) {
    if(isUnix()) {
        sh nixCommand
    } else {
        bat winCommand
    }
}
def chooseShellByPlatformMacWinLin(List macWinLinCommands, platform) {
    if(isWindows(platform)) {
        bat macWinLinCommands[1]
    } else if(isMac(platform)) {
        sh macWinLinCommands[0]
    } else {
        sh macWinLinCommands[2]
    }
}


