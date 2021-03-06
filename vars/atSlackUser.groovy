def call() {
    def userCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
    if(userCause != null) {
        String jenkinsUserName = userCause.getUserId()
        String slackUserId = ''
             if(jenkinsUserName == 'austin')           { slackUserId = 'UAGV8R9PS' }
        else if(jenkinsUserName == 'ben')              { slackUserId = 'UAHHSRPD5' }
        else if(jenkinsUserName == 'chris')            { slackUserId = 'UAG89NX9S' }
        else if(jenkinsUserName == 'dcareri')          { slackUserId = 'UCSEAQVJR' }
        else if(jenkinsUserName == 'devans')           { slackUserId = 'U017WJ8EZTP' }
        else if(jenkinsUserName == 'jennifer')         { slackUserId = 'UAFN64MEC' }
        else if(jenkinsUserName == 'joerg')            { slackUserId = 'UAHNGEP61' }
        else if(jenkinsUserName == 'justsid')          { slackUserId = 'UAFUMQESC' }
        else if(jenkinsUserName == 'michael')          { slackUserId = 'UKDNBTT2R' }
        else if(jenkinsUserName == 'michael.minnhaar') { slackUserId = 'UKDNBTT2R' }
        else if(jenkinsUserName == 'otherphilipp')     { slackUserId = 'UAHMBUCV9' }
        else if(jenkinsUserName == 'petr')             { slackUserId = 'UAHFVHYUC' }
        else if(jenkinsUserName == 'philipp')          { slackUserId = 'UAHMBUCV9' }
        else if(jenkinsUserName == 'phillipother')     { slackUserId = 'UAHMBUCV9' }
        else if(jenkinsUserName == 'pmccarty')         { slackUserId = 'U011LLC170D' }
        else if(jenkinsUserName == 'sidney.just')      { slackUserId = 'UAFUMQESC' }
        else if(jenkinsUserName == 'stefan')           { slackUserId = 'UDNLZMEET' }
        else if(jenkinsUserName == 'tngreene')         { slackUserId = 'UAHFTKPFT' }
        else if(jenkinsUserName == 'tyler')            { slackUserId = 'UAG6R8LHJ' }

        if(slackUserId) {
            return "<@${slackUserId}>"
        }
    }
    return ''
}
