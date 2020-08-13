package gpp.sso.service.database

import gpp.sso.model._

sealed abstract class RoleRequest(
  val tpe: RoleType,
  val partnerOption: Option[Partner] = None
)
object RoleRequest {
  final case object Pi extends RoleRequest(RoleType.Pi)
  final case class  Ngo(partner: Partner) extends RoleRequest(RoleType.Ngo, Some(partner))
  final case object Staff extends RoleRequest(RoleType.Staff)
  final case object Admin extends RoleRequest(RoleType.Admin)
}
