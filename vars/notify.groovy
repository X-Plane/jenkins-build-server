def call(String subj='', String msg='', String errorMsg='', String recipient='') { // empty recipient means we'll send to the most likely suspects
    String summary = errorMsg.isEmpty() ?
            "Download the build products: ${BUILD_URL}artifact/*zip*/archive.zip" :
            "The error was: ${errorMsg}"

    String body = """${msg}
    
${summary}
        
Build URL: ${BUILD_URL}

Console Log (split by machine/task/subtask): ${BUILD_URL}flowGraphTable/

Console Log (plain text): ${BUILD_URL}console
"""
    boolean buildTriggeredByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
    String recipientProviderClass = buildTriggeredByUser ? 'RequesterRecipientProvider' : 'CulpritsRecipientProvider'
    emailext attachLog: true,
            body: body,
            subject: subj,
            to: recipient ? recipient : emailextrecipients([
                    [$class: recipientProviderClass]
            ])
}

