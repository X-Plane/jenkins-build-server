def call(String message, String color='danger') {
    String atUser = atSlackUser()
    if(atUser) {
        try {
            slackSend(
                    color: color,
                    message: "$atUser $message")
            return true
        } catch(e) { }
    }
    return false
}
