def call(List<String> products=[], String dropboxPath='', boolean writeOnce=true, utils=null, boolean move_instead_of_copy=true) {
    archiveArtifacts artifacts: products.join(', '), fingerprint: true, onlyIfSuccessful: false

    String destSlashesEscaped = utils.escapeSlashes(dropboxPath)
    for(String p : products) {
        // If the user asked for write-once behavior, do *NOT* copy to Dropbox if the products already exist!
        if(writeOnce && fileExists(destSlashesEscaped + p)) {
            echo "Skipping copy of ${p} to Dropbox, since the file already exists in ${destSlashesEscaped}"
        } else if(move_instead_of_copy) {
            utils.moveFilePatternToDest(p, destSlashesEscaped)
        } else {
            utils.copyFilePatternToDest(p, destSlashesEscaped)
        }
    }
}
