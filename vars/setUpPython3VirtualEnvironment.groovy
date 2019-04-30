def call(utils, String platform='') {
    String dirChar = utils.getDirChar(platform)
    String python3Path = utils.chooseByPlatformNixWin('python3', '"C:\\Program Files\\Python37\\python.exe"', platform)
    String binDir = utils.chooseByPlatformNixWin('bin', 'Scripts', platform)
    try {
        utils.chooseShell("virtualenv env -p ${python3Path}", platform)
        utils.chooseShell("env${dirChar}${binDir}${dirChar}pip install -r package_requirements.txt", platform)
    } catch(e) {
        utils.notify("Virtual environment setup failed", "Check the logs.", e.toString(), "tyler@x-plane.com")
        throw e
    }
}
