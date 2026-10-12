TOMCAT 11.0.26 - EXPLICIT WAR DEPLOYMENT EXAMPLE

This is a customized binary distribution of Apache Tomcat 11.0.26.
It is intended for the user's Ubuntu VM. Java 17 or newer is required.

WHAT CHANGED
1. conf/server.xml lists the application WAR in a Context element.
2. Automatic webapps scanning is disabled: autoDeploy=false and
   deployOnStartup=false. Explicit Context elements still start normally.
3. Manager and Host Manager applications, their embedded access configuration,
   ROOT, examples and docs are omitted from webapps. webapps starts empty.
4. conf/tomcat-users.xml has no users or roles. The authentication Realm is
   retained so that an application's security constraints are not bypassed.
5. apps/application.war is a small Hello World JSP example. It has no login.
   Removing Tomcat administrative access does not remove an application's own
   login screen or accounts. Jenkins, for example, manages its own accounts.
6. Original Apache LICENSE, NOTICE, libraries and startup scripts are retained.

START ON UBUNTU
Copy apache-tomcat-11.0.26-war-only.tar.gz to your VM, then run:

  java -version
  tar -xzf apache-tomcat-11.0.26-war-only.tar.gz
  cd apache-tomcat-11.0.26-war-only
  ./bin/startup.sh

Run Tomcat as your normal non-root Linux user. If Java is not found, set
JAVA_HOME to your installed JDK directory before starting Tomcat. If an
unrelated CATALINA_HOME, CATALINA_BASE or JRE_HOME is already set in the
terminal, correct it for this instance before starting it.

Open http://VM-IP:8080/app/ in your browser; for the previously used VM address:
  http://192.168.0.123:8080/app/

Network access depends on the VM network and Ubuntu firewall configuration.
The Connector uses plain HTTP. This example does not install a service or TLS.

The /manager/html and /host-manager/html URLs return HTTP 404. There is no
default ROOT application, so / also returns HTTP 404.

STOP
  ./bin/shutdown.sh

USE YOUR OWN WAR
Stop Tomcat first. Keep a backup of your application outside this directory.
Copy your WAR to apps/application.war, replacing the example, and restart.
The URL remains /app/. Alternatively, edit conf/server.xml:

  <Context path="/myapp" docBase="/home/your-user/deploy/myapp.war"
           reloadable="false" override="true" />

Use a path readable by the Linux user running Tomcat. Paths are case-sensitive
on Ubuntu. Each Context must have a unique path. For the root URL, use path="".
Do not point docBase at a JAR. Tomcat deploys WAR files or expanded web apps.
Libraries belong inside the WAR's WEB-INF/lib directory. A self-contained
executable JAR is normally launched separately with java -jar.

ADD MORE WAR FILES
Add one Context per WAR inside the Host element in conf/server.xml, for example:

  <Context path="/orders" docBase="${catalina.base}/apps/orders.war"
           reloadable="false" override="true" />

Copy orders.war into apps and restart Tomcat. Merely copying files into apps
or webapps does not deploy them in this configuration. An XML edit or WAR
replacement requires a restart. Tomcat may create expanded application files
under webapps and compiled JSPs under work; do not edit those generated files.

TROUBLESHOOTING
  ./bin/catalina.sh configtest
  tail -n 100 logs/catalina.out

If port 8080 or the local shutdown port 8005 is already in use, stop the other
instance or change the relevant port in conf/server.xml. Java 17+ and a WAR
compatible with Tomcat 11's Jakarta Servlet 6.1 APIs are required. Older WARs
that depend on javax.servlet generally require migration/rebuilding. The
sample JSP WAR does not require Maven or a compiler on the VM.

Tomcat recommends individual application Context XML files for general use.
This example intentionally uses server.xml to match the user's requested
deployment method; editing server.xml requires restarting the server.

FILES INCLUDED NEXT TO THE PACKAGE
- Original Apache archive and checksum: downloads/
- Sample WAR for reuse: application.war
- Modified configuration: conf/server.xml, conf/tomcat-users.xml
- BUILD-INFO.json: upstream checksum verification and package modifications
- VALIDATION.json: local runtime and HTTP verification results

The sample source and package build/validation scripts are in tomcat-example
in the workspace. No connection or installation on the Ubuntu VM is performed.

REFERENCES
https://tomcat.apache.org/tomcat-11.0-doc/config/context.html
https://tomcat.apache.org/tomcat-11.0-doc/RUNNING.txt
https://tomcat.apache.org/tomcat-11.0-doc/security-howto.html
