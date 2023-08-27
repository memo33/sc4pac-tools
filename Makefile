dist:
	sh ./src/scripts/dist.sh

channel:
	./sc4pac build-channel ./channel
channel-testing:
	./sc4pac build-channel ./channel-testing

host:
	# jwebserver comes with java 18
	cd channel-testing/json/ && jwebserver -p 8090 -o info

# sbt:
# 	sbt -Dcoursier.credentials="$(realpath sc4pac-credentials.properties)"

clean:
	rm -rf plugins temp sc4pac-plugins.json sc4pac-plugins-lock.json
clean-cache: clean
	rm -rf cache

.PHONY: dist channel channel-testing host clean clean-cache
