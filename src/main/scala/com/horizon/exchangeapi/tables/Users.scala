package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi._
import slick.jdbc.PostgresProfile.api._

/** Contains the object representations of the DB tables related to users. */

//future: figure out how to use the slick type Timestamp, but have it stored in UTC
// case class UserRow(username: String, password: String, email: String, lastUpdated: Timestamp) {
case class UserRow(username: String, orgid: String, password: String, email: String, lastUpdated: String) {
  def insertUser(): DBIO[_] = {
    val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.rows += (UserRow(username, orgid, pw, email, lastUpdated))
  }

  def upsertUser: DBIO[_] = {
    val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.rows.insertOrUpdate(UserRow(username, orgid, pw, email, lastUpdated))
  }

  def updateUser(): DBIO[_] = {
    val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    // if password and/or email are blank, it means they should not be updated
    (pw, email) match {
      case ("", "") => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.orgid,u.lastUpdated)).update((username, orgid, lastUpdated))
      case (_, "") => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.orgid,u.password,u.lastUpdated)).update((username, orgid, pw, lastUpdated))
      case ("", _) => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.orgid,u.email,u.lastUpdated)).update((username, orgid, email, lastUpdated))
      case (_, _) => return (for { u <- UsersTQ.rows if u.username === username } yield u).update(UserRow(username, orgid, pw, email, lastUpdated))
    }
  }
}

/** Mapping of the users db table to a scala class */
class Users(tag: Tag) extends Table[UserRow](tag, "users") {
  def username = column[String]("username", O.PrimaryKey)    // the content of this is orgid/username
  def orgid = column[String]("orgid")
  def password = column[String]("password")
  def email = column[String]("email")
  // def lastUpdated = column[Timestamp]("lastupdated")    //todo: need this is UTC, not local time zone
  def lastUpdated = column[String]("lastupdated")
  def * = (username, orgid, password, email, lastUpdated) <> (UserRow.tupled, UserRow.unapply)
  //def primKey = primaryKey("pk_pk", (username, orgid))
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object UsersTQ {
  // Instance to access the users table
  val rows = TableQuery[Users]

  //def getAllUsers(orgid: String) = rows.filter(_.username like orgid+"/%")
  def getAllUsers(orgid: String) = rows.filter(_.orgid === orgid)
  def getUser(username: String) = rows.filter(_.username === username)
  def getPassword(username: String) = rows.filter(_.username === username).map(_.password)
  def getEmail(username: String) = rows.filter(_.username === username).map(_.email)
}

case class User(password: String, email: String, lastUpdated: String) {
  def hidePassword = User(StrConstants.hiddenPw, email, lastUpdated)
}
