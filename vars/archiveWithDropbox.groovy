def call(List<String> products=[], String dropboxPath='', boolean writeOnce=true, utils=null) {
    archiveArtifacts artifacts: products.join(', '), fingerprint: true, onlyIfSuccessful: false

    String destSlashesEscaped = utils.escapeSlashes(dropboxPath)
    for(String p : products) {
        // If the user asked for write-once behavior, do *NOT* copy to Dropbox if the products already exist!
        if(writeOnce && fileExists(destSlashesEscaped + p)) {
            echo "Skipping copy of ${p} to Dropbox, since the file already exists in ${dest}"
        } else {
            utils.moveFilePatternToDest(p, destSlashesEscaped)
        }
    }
}
