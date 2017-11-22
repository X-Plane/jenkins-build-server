def call(String branchName='', String checkoutDir='', String platform='', String repo='ssh://tyler@dev.x-plane.com/admin/git-xplane/design.git') {
    dir(checkoutDir) {
        echo "Checking out ${branchName} on ${platform}"
        if(platform == 'Linux') {
            sshagent(['tylers-ssh']) {
                sh "git branch"
                sh "git fetch"
                sh "git checkout ${branchName}"
                try {
                    sh "git pull"
                } catch(e) { } // If we're in detached HEAD mode, pull will fail
            }
        } else {
            checkout(
                    [$class: 'GitSCM', branches: [[name: branchName]],
                     doGenerateSubmoduleConfigurations: false,
                     extensions: [
                             [$class: 'BuildChooserSetting', buildChooser: [$class: 'AncestryBuildChooser', ancestorCommitSha1: '', maximumAgeInDays: 120]]
                     ],
                     submoduleCfg: [],
                     userRemoteConfigs:  [[credentialsId: 'tylers-ssh', url: repo]]]
            )
        }

        String commitId = ""
        if(isUnix()) {
            commitId = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        } else {
            commitId = bat(returnStdout: true, script: "git rev-parse HEAD").trim().split("\r?\n")[1]
        }
        echo "Checked out commit ${commitId}"
    }
}

