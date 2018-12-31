def call(utils, String platform='') {
    String binDir = utils.chooseByPlatformNixWin('bin', 'Scripts', platform)
    try {
        utils.chooseShell('virtualenv env -p python3', platform)
        utils.chooseShell("env/${binDir}/pip install -r package_requirements.txt", platform)
    } catch(e) {
        utils.notify("Virtual environment setup failed", "Check the logs.", e.toString(), "tyler@x-plane.com")
        throw e
    }
}
