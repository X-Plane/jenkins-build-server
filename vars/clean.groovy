def call(List<String> productsToClean=[], List<String> macWinLinCleanCommmand=[], String platform='', utils=null) {
    if(productsToClean || macWinLinCleanCommmand) {
        dir(utils.getCheckoutDir(platform)) {
            utils.nukeIfExist(productsToClean, platform)
            if(macWinLinCleanCommmand) {
                try {
                    utils.chooseShellByPlatformMacWinLin(macWinLinCleanCommmand, platform)
                } catch (e) { }
            }
        }
    }
}

