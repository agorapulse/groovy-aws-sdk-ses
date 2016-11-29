package com.agorapulse.awssdk.ses

import static com.agorapulse.awssdk.ses.AwsSdkSesEmailDeliveryStatus.STATUS_DELIVERED
import static com.agorapulse.awssdk.ses.AwsSdkSesEmailDeliveryStatus.STATUS_BLACKLISTED
import static com.agorapulse.awssdk.ses.AwsSdkSesEmailDeliveryStatus.STATUS_NOT_DELIVERED

import com.agorapulse.awssdk.AwsSdkUtils
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService

import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.AmazonWebServiceClient
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient
import com.amazonaws.services.simpleemail.model.Body
import com.amazonaws.services.simpleemail.model.Content
import com.amazonaws.services.simpleemail.model.Destination
import com.amazonaws.services.simpleemail.model.Message
import com.amazonaws.services.simpleemail.model.RawMessage
import com.amazonaws.services.simpleemail.model.SendEmailRequest
import com.amazonaws.services.simpleemail.model.SendRawEmailRequest
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.BodyPart
import javax.mail.Session
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import java.nio.ByteBuffer

@Slf4j
@CompileStatic
class AwsSesMailer {
    AmazonWebServiceClient client

    void initClient(String accessKey, String secretKey, String regionName) {
        Region region = RegionUtils.getRegion(regionName)
        if ( !region?.isServiceSupported(AmazonSimpleEmailService.ENDPOINT_PREFIX) ) {
            log.error("${AmazonSimpleEmailService.ENDPOINT_PREFIX} is not supported in region $regionName")
            return
        }
        def credentials = new BasicAWSCredentials(accessKey, secretKey)
        def clientConfiguration = AwsSdkUtils.clientConfigurationWithMap([:])
        client = new AmazonSimpleEmailServiceClient(credentials, clientConfiguration).withRegion(region)
    }

    /**
     * @return 1 if successful, 0 if not sent, -1 if blacklisted
     */
    int mail(@DelegatesTo(TransactionalEmail) Closure composer) throws Exception {

        def transactionalEmail = transactionalEmailWithClosure(composer)
        send(transactionalEmail.destinationEmail,
                transactionalEmail.subject,
                transactionalEmail.htmlBody,
                transactionalEmail.sourceEmail,
                transactionalEmail.replyToEmail)
    }

    int mailWithAttachment(@DelegatesTo(TransactionalEmail) Closure composer)
            throws UnsupportedAttachmentTypeException {
        def transactionalEmail = transactionalEmailWithClosure(composer)
        sendEmailWithAttachment(transactionalEmail)
    }

    @SuppressWarnings(['LineLength', 'ElseBlockBraces', 'JavaIoPackageAccess'])
    int sendEmailWithAttachment(TransactionalEmail transactionalEmail) throws UnsupportedAttachmentTypeException {
        int statusId = STATUS_NOT_DELIVERED

        Session session = Session.getInstance(new Properties())
        MimeMessage mimeMessage = new MimeMessage(session)
        def subject = transactionalEmail.subject
        mimeMessage.setSubject(subject)
        MimeMultipart mimeMultipart = new MimeMultipart()

        BodyPart p = new MimeBodyPart()
        p.setContent(transactionalEmail.htmlBody, 'text/html')
        mimeMultipart.addBodyPart(p)

        for ( TransactionalEmailAttachment attachment : transactionalEmail.attachments ) {

            if ( !AwsSdkSesMimeType.isMimeTypeSupported(attachment.mimeType) ) {
                throw new UnsupportedAttachmentTypeException()
            }

            MimeBodyPart mimeBodyPart = new MimeBodyPart()
            mimeBodyPart.setFileName(attachment.filename)
            mimeBodyPart.setDescription(attachment.description, 'UTF-8')
            DataSource ds = new ByteArrayDataSource(new FileInputStream(new File(attachment.filepath)),
                    attachment.mimeType)
            mimeBodyPart.setDataHandler(new DataHandler(ds))
            mimeMultipart.addBodyPart(mimeBodyPart)
        }
        mimeMessage.content = mimeMultipart

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        mimeMessage.writeTo(outputStream)
        RawMessage rawMessage = new RawMessage(ByteBuffer.wrap(outputStream.toByteArray()))

        SendRawEmailRequest rawEmailRequest = new SendRawEmailRequest(rawMessage)

        rawEmailRequest.setDestinations(transactionalEmail.recipients)
        rawEmailRequest.setSource(transactionalEmail.sourceEmail)

        try {
            (client as AmazonSimpleEmailServiceClient).sendRawEmail(rawEmailRequest)
            statusId = STATUS_DELIVERED

        } catch (AmazonServiceException exception) {
            if ( exception.message.find('Address blacklisted') ) {

                log.debug "Address blacklisted destinationEmail=${transactionalEmail.recipients.toString()}"
                statusId = STATUS_BLACKLISTED

            } else if ( exception.message.find('Missing final') ) {
                log.warn "Invalid parameter value: destinationEmail=${transactionalEmail.recipients.toString()}, sourceEmail=${transactionalEmail.sourceEmail}, replyToEmail=${transactionalEmail.replyToEmail}, subject=${subject}"

            } else {
                log.warn 'An amazon service exception was catched while sending email with attachment' + exception.message
            }

        } catch (AmazonClientException exception) {
            log.warn 'An amazon client exception was catched while sending email with attachment' + exception.message

        }
        statusId
    }

    /**
     *
     * @param destinationEmail
     * @param subject
     * @param htmlBody
     * @param sourceEmail
     * @param replyToEmail
     * @return 1 if successful, 0 if not sent, -1 if blacklisted
     */
    @SuppressWarnings(['LineLength', 'ElseBlockBraces'])
    int send(String destinationEmail,
             String subject,
             String htmlBody,
             String sourceEmail = '',
             String replyToEmail = '') {
        int statusId = STATUS_NOT_DELIVERED
        if ( !destinationEmail ) {
            return statusId
        }

        Destination destination = new Destination([destinationEmail])
        Content messageSubject = new Content(subject)
        Body messageBody = new Body().withHtml(new Content(htmlBody))
        Message message = new Message(messageSubject, messageBody)
        try {
            SendEmailRequest sendEmailRequest = new SendEmailRequest(sourceEmail, destination, message)
            if ( replyToEmail ) {
                sendEmailRequest.replyToAddresses = [replyToEmail]
            }
            (client as AmazonSimpleEmailServiceClient).sendEmail(sendEmailRequest)
            statusId = STATUS_DELIVERED
        } catch (AmazonServiceException exception) {

            if (exception.message.find('Address blacklisted')) {
                log.debug "Address blacklisted destinationEmail=$destinationEmail"
                statusId = STATUS_BLACKLISTED

            } else if (exception.message.find('Missing final')) {
                log.warn "An amazon service exception was catched while sending email: destinationEmail=$destinationEmail, sourceEmail=$sourceEmail, replyToEmail=$replyToEmail, subject=$subject"

            } else {
                log.warn 'An amazon service exception was catched while send +ng email' + exception.message
            }

        } catch (AmazonClientException exception) {
            log.warn 'An amazon client exception was catched while sending email' + exception.message
        }
        statusId
    }

    static TransactionalEmail transactionalEmailWithClosure(@DelegatesTo(TransactionalEmail) Closure composer) {
        Closure cl = composer.clone() as Closure
        TransactionalEmail transactionalEmail = new TransactionalEmail()
        cl.delegate = transactionalEmail
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        transactionalEmail
    }

}
