BUILDDIR		= ./build
LIBDIR          = ./lib
JACKDIR         = $(LIBDIR)/Jackson
COMMDIR         = $(LIBDIR)/CommonsApache
SRVRDIR			= ./Server
DATADIR			= /data
CLIENTDIR       = ./Client
JACKSON			= $(JACKDIR)/*
COMMAPA			= $(COMMDIR)/*
ADDRESS			= 127.0.0.1
ARGADDRESS		= -h $(ADDRESS)
PORT			= 10002
ARGPORT			= -p $(PORT)
COMP			= javac
EX				= java
DEBUG			= 
CPATH        	= -cp


.PHONY: both compileServer compileClient runServer runClient Client Server clean

both: compileServer compileClient
	@echo DONE

#Anteponendo @ ai comandi evito che vengano stampati su stdout prima di essere eseguiti
#Con cd mi sposto nelle directories e, per separare solo fisicamente e non logicamente i comandi, ho utilizzato ;\ 

#PHONY PER COMPILARE IL SERVER
compileServer:
	@cd $(SRVRDIR);\
	$(COMP) -d $(BUILDDIR) $(CPATH) ".:$(JACKSON):$(COMMAPA):" MainServer.java

#PHONY PER COMPILARE IL CLIENT
compileClient:
	@cd $(CLIENTDIR);\
	$(COMP) -d $(BUILDDIR) $(CPATH) ".:$(COMMAPA):" MainClient.java

#PHONY PER ESEGUIRE IL SERVER
runServer:
	@cd $(SRVRDIR);\
	$(EX) $(CPATH) ".:$(BUILDDIR):$(JACKSON):$(COMMAPA):" MainServer $(ARGPORT) $(ARGADDRESS) $(DEBUG)

#PHONY PER ESEGUIRE IL CLIENT
runClient:
	@cd $(CLIENTDIR);\
	$(EX) $(CPATH) ".:$(BUILDDIR):$(COMMAPA):" MainClient $(ARGPORT) $(ARGADDRESS)

#PHONY PER COMPILARE ED ESEGUIRE IL SERVER
Server: compileServer runServer

#PHONY PER COMPILARE Ed ESEGUIRE IL CLIENT
Client: compileClient runClient

#PHONY DI PULIZIA DELLA CARTELLA DATA CONTENENTE UTENTI E PROGETTI
clean:
	@rm -r $(SRVRDIR)$(DATADIR)