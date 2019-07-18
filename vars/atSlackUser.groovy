def call() {
    def userCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
    if(userCause != null) {
        String jenkinsUserName = userCause.getUserId()
        String slackUserId = ''
             if(jenkinsUserName == 'jennifer') { slackUserId = 'UAFN64MEC' }
        else if(jenkinsUserName == 'tyler')    { slackUserId = 'UAG6R8LHJ' }
        else if(jenkinsUserName == 'justsid')  { slackUserId = 'UAFUMQESC' }
        else if(jenkinsUserName == 'chris')    { slackUserId = 'UAG89NX9S' }
        else if(jenkinsUserName == 'philipp')  { slackUserId = 'UAHMBUCV9' }
        else if(jenkinsUserName == 'ben')      { slackUserId = 'UAHHSRPD5' }
        else if(jenkinsUserName == 'joerg')    { slackUserId = 'UAHNGEP61' }
        else if(jenkinsUserName == 'austin')   { slackUserId = 'UAGV8R9PS' }
        else if(jenkinsUserName == 'dcareri')  { slackUserId = 'UCSEAQVJR' }

        if(slackUserId) {
            return "<@${slackUserId}>"
        }
    }
    return ''
}
