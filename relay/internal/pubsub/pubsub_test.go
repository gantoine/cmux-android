package pubsub

import "testing"

func TestNotifyReachesSubscribersUntilTheyLeave(t *testing.T) {
	var broadcaster Broadcaster
	first, leaveFirst := broadcaster.Subscribe()
	second, leaveSecond := broadcaster.Subscribe()
	defer leaveSecond()

	broadcaster.Notify()
	broadcaster.Notify()
	for _, channel := range []<-chan struct{}{first, second} {
		select {
		case <-channel:
		default:
			t.Fatal("a subscriber missed the change")
		}
		select {
		case <-channel:
			t.Fatal("changes should coalesce into one signal")
		default:
		}
	}

	leaveFirst()
	broadcaster.Notify()
	select {
	case <-first:
		t.Fatal("an unsubscribed channel was signalled")
	default:
	}
	<-second
}
