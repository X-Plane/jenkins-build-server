def call(sendEmailF=null, String app='', String branchName='', String commitId='', String platform='', Exception e=null) {
    sendEmailF("Build of ${app} on ${platform} is broken [${branchName}; ${commitId}]",
            "${platform} build of ${app} commit ${commitId} from the branch ${branchName} failed.",
            e.toString())
    throw e
}

