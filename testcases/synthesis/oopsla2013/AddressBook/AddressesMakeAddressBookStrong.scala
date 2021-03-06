import leon.annotation._
import leon.lang._
import leon.lang.synthesis._

object Addresses {
  case class Info(
    address: Int,
    zipcode: Int,
    local: Boolean
  )
  
  case class Address(info: Info, priv: Boolean)
  
  sealed abstract class List
  case class Cons(a: Address, tail:List) extends List
  case object Nil extends List

  def content(l: List) : Set[Address] = l match {
    case Nil => Set.empty[Address]
    case Cons(addr, l1) => Set(addr) ++ content(l1)
  }

	def size(l: List) : Int = l match {
	  case Nil => 0
	  case Cons(head, tail) => 1 + size(tail)
	}
	
  def allPrivate(l: List): Boolean = l match {
    case Nil => true
    case Cons(a, l1) =>
      if (a.priv) allPrivate(l1)
      else false
  }
	
  def allBusiness(l: List): Boolean = l match {
    case Nil => true
    case Cons(a, l1) =>
      if (a.priv) false
      else allBusiness(l1)
  }

  case class AddressBook(business : List, pers : List)
  
  def sizeA(ab: AddressBook): Int = size(ab.business) + size(ab.pers)
  		 
//  def isEmpty(ab: AddressBook) = sizeA(ab) == 0
  
  def contentA(ab: AddressBook) : Set[Address] = content(ab.pers) ++ content(ab.business)
  
  def addressBookInvariant(ab: AddressBook) = allPrivate(ab.pers) && allBusiness(ab.business)
  
  
//  def makeAddressBook(l: List): AddressBook = (l match {
//    case Nil => AddressBook(Nil, Nil)
//    case Cons(a, l1) => {
//      val res = makeAddressBook(l1)
//      if (a.priv) AddressBook(res.business, Cons(a, res.pers))
//      else AddressBook(Cons(a, res.business), res.pers)
//    }
//  }) ensuring {
//    res =>
//	  size(res) == size(l) &&
//	  !hasPrivate(res.business) &&
//	  hasPrivate(res.pers)
//  }
  
//  def makeAddressBook(l: List): AddressBook = (l match {
//    case Nil => AddressBook(Nil, Nil)
//    case Cons(a, l1) => {
//      val res = makeAddressBook(l1)
//      if (a.priv) AddressBook(res.business, Cons(a, res.pers))
//      else AddressBook(Cons(a, res.business), res.pers)
//    }
//  }) ensuring {
//    res =>
//	  size(res) == size(l) &&
//	  (if (size(res.business) > 0) {
//	    !hasPrivate(res.business)
//	  } else true ) &&
//	  (if (size(res.pers) > 0) {
//	    hasPrivate(res.pers)
//	  } else true )
//  }
//  
//  def makeAddressBook(l: List): AddressBook = 
//		choose {
//    (res: AddressBook) =>
//		  size(res) == size(l) &&
//		  (if (size(res.business) > 0) {
//		    !hasPrivate(res.business)
//		  } else true ) &&
//		  (if (size(res.pers) > 0) {
//		    hasPrivate(res.pers)
//		  } else true )
//  }

/* 
 // MANUAL SOLUTION:
  def makeAB(l:List) : AddressBook = {
    l match {
      case Nil => AddressBook(Nil,Nil)
      case Cons(a,t) => {
	val ab1 = makeAB(t)
	if (a.priv) AddressBook(ab1.business, Cons(a,ab1.pers))
	else AddressBook(Cons(a,ab1.business), ab1.pers)
      }
    }
  } ensuring((res: AddressBook) =>
	        sizeA(res) == size(l) && 
                addressBookInvariant(res) &&
                contentA(res) == content(l))

*/

/* // Perhaps this can be useful?
  def cond(b:Boolean, ab1: AddressBook, ab2: AddressBook) : AddressBook = {
    if (b) ab1 else ab2
  }
*/

  def makeAddressBook(l: List): AddressBook = choose {
    (res: AddressBook) =>
      sizeA(res) == size(l) && 
      addressBookInvariant(res) &&
      contentA(res) == content(l)
  }

}
