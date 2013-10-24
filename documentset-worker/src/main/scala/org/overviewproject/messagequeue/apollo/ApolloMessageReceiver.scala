package org.overviewproject.messagequeue.apollo

import akka.actor._
import org.overviewproject.jobhandler.MessageContainer
import org.overviewproject.jobhandler.ApolloMessageService2
import org.overviewproject.jobhandler.MessageReceiver

object ApolloMessageReceiver {

  def apply[T](messageRecipient: ActorRef, 
               queueName: String, 
               messageConverter: String => T): Props = {
    val messageService = new ApolloMessageService2(queueName)
    
    Props(new MessageReceiver[T](messageRecipient, messageService, messageConverter))
  }
}