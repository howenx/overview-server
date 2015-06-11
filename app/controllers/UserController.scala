package controllers

import controllers.auth.AuthorizedAction
import controllers.auth.Authorities.anyUser
import org.squeryl.SquerylSQLException
import play.api.data.Form
import play.api.mvc.RequestHeader

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

import controllers.backend.UserBackend
import controllers.util.TransactionAction
import models.{ OverviewUser, ConfirmationRequest, PotentialNewUser }
import models.orm.stores.UserStore

trait UserController extends Controller {
  val loginForm: Form[OverviewUser] = controllers.forms.LoginForm()
  val userForm: Form[PotentialNewUser] = controllers.forms.UserForm()

  private val m = views.Magic.scopedMessages("controllers.UserController")

  def new_ = SessionController.new_

  def create = TransactionAction { implicit request =>
    userForm.bindFromRequest().fold(
      formWithErrors => BadRequest(views.html.Session.new_(loginForm, formWithErrors)),
      registration => {
        registration.withRegisteredEmail match {
          case Some(u) => handleExistingUser(u)
          case None => handleNewUser(registration)
        }
        Redirect(routes.ConfirmationController.index(registration.email)).flashing(
          "event" -> "user-create"
        )
      })
  }

  /**
   *
   */
  def show() = AuthorizedAction(anyUser)({ implicit request =>
    Ok(views.html.User.show(request.user))
  })

  /** Sends an email to an existing user saying someone tried to log in. */
  protected def mailExistingUser(user: OverviewUser)(implicit request: RequestHeader): Unit

  /** Sends an email to a new user with a confirmation link. */
  protected def mailNewUser(user: OverviewUser with ConfirmationRequest)(implicit request: RequestHeader): Unit

  /**
   * Adds the user to the database, with a confirmation token.
   *
   * @throws SquerylSQLException with Cause SQLException with SQLState "23505"
   *        (unique key violation) if there's a race and the user has recently
   *        been saved.
   */
  protected def saveUser(user: OverviewUser with ConfirmationRequest): OverviewUser with ConfirmationRequest

  private def handleNewUser(user: PotentialNewUser)(implicit request: RequestHeader): Unit = {
    val sqlStateUniqueKeyViolation: String = "23505"
    val userWithRequest = user.requestConfirmation

    try {
      saveUser(userWithRequest)
      mailNewUser(userWithRequest)
    } catch {
      case e: SquerylSQLException => {
        val sqlState = e.getCause.getSQLState()
        if (sqlStateUniqueKeyViolation.equals(sqlState)) {
          /*
           * There's a duplicate key in the database. This means the email has
           * already been saved. But the only way to get to this code path is a
           * race: the email wasn't there when userForm.bindFromRequest was
           * called (it calls PotentialUser.apply, which does the lookup).
           *
           * If the process arrives here, some *other* thread of execution
           * *didn't*, and it's sending a confirmation email. We can just
           * pretend the database request went as planned, since from the
           * user's perspective, it did.
           */
        } else {
          throw e
        }
      }
    }
  }

  private def handleExistingUser(user: OverviewUser)(implicit request: RequestHeader): Unit = {
    mailExistingUser(user)
  }

  protected val backend: UserBackend
}

object UserController extends UserController {
  override protected val backend = UserBackend

  override protected def saveUser(user: OverviewUser with ConfirmationRequest): OverviewUser with ConfirmationRequest = {
    val savedUser = OverviewUser(UserStore.insertOrUpdate(user.toUser))
    savedUser.withConfirmationRequest.getOrElse(throw new Exception("impossible"))
  }

  override protected def mailNewUser(user: OverviewUser with ConfirmationRequest)(implicit request: RequestHeader) = {
    mailers.User.create(user).send
  }

  override protected def mailExistingUser(user: OverviewUser)(implicit request: RequestHeader) = {
    mailers.User.createErrorUserAlreadyExists(user).send
  }
}
