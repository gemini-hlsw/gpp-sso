// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.sso.service.database

import enumeratum._
import enumeratum.EnumEntry.Lowercase

// lucuma_role_type
sealed trait RoleType extends EnumEntry with Lowercase
object RoleType extends Enum[RoleType] {
  case object Pi    extends RoleType
  case object Ngo   extends RoleType
  case object Staff extends RoleType
  case object Admin extends RoleType
  val values = findValues
}
