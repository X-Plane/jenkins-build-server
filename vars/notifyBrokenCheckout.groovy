def call(sendEmailF=null, String app = '', String branchName = '', String platform = '', Exception err=null) {
    sendEmailF("Jenkins Git checkout is broken for ${app} on ${platform} [${branchName}]",
            "${platform} Git checkout for ${app} failed on branch ${branchName}. We will be unable to continue until this is fixed.",
            err.toString())
    throw err
}
